package controllers

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import javax.inject.{Inject, Named}

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import com.mohiva.play.silhouette.api.{LogoutEvent, Silhouette}
import com.mohiva.play.silhouette.impl.providers.SocialProviderRegistry
import kamon.Kamon
import play.api.mvc._
import play.api.{Configuration, Environment, Logger, Mode}
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile
import slick.jdbc.meta.MTable
import upickle.default._
import upickle.{Js, json}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}
import scalafiddle.server._
import scalafiddle.server.dao.{Fiddle, FiddleDAL}
import scalafiddle.server.models.User
import utils.auth.{AllLoginProviders, DefaultEnv}
import scalafiddle.shared.{Api, FiddleData, Library, UserInfo}

object Router extends autowire.Server[Js.Value, Reader, Writer] {
  override def read[R: Reader](p: Js.Value) = readJs[R](p)
  override def write[R: Writer](r: R)       = writeJs(r)
}

class Application @Inject()(
    implicit val config: Configuration,
    env: Environment,
    silhouette: Silhouette[DefaultEnv],
    socialProviderRegistry: SocialProviderRegistry,
    actorSystem: ActorSystem,
    @Named("persistence") persistence: ActorRef
) extends Controller {
  implicit val timeout = Timeout(15.seconds)
  val log              = Logger(getClass)
  val libUri           = config.getString("scalafiddle.librariesURL").get

  Kamon.start()

  val indexCounter  = Kamon.metrics.counter("index-load")
  val fiddleCounter = Kamon.metrics.counter("fiddle-load")

  def libSource() = {
    if (libUri.startsWith("file:")) {
      // load from file system
      scala.io.Source.fromFile(libUri.drop(5), "UTF-8")
    } else if (libUri.startsWith("http")) {
      System.setProperty(
        "http.agent",
        "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/28.0.1500.29 Safari/537.36")
      scala.io.Source.fromURL(libUri, "UTF-8")
    } else {
      env.resourceAsStream(libUri).map(s => scala.io.Source.fromInputStream(s, "UTF-8")).get
    }
  }

  val librarian = new Librarian(libSource)
  // refresh libraries every N minutes
  actorSystem.scheduler.schedule(config.getInt("scalafiddle.refreshLibraries").get.seconds,
                                 config.getInt("scalafiddle.refreshLibraries").get.seconds)(librarian.refresh())
  val defaultSource = config.getString("scalafiddle.defaultSource").get

  if (env.mode != Mode.Prod)
    createTables()

  def index(fiddleId: String, version: String) = silhouette.UserAwareAction.async { implicit request =>
    if (fiddleId.isEmpty) {
      indexCounter.increment()
    } else {
      fiddleCounter.increment()
      persistence ! AddAccess(fiddleId,
                              version.toInt,
                              request.identity.map(_.userID),
                              embedded = false,
                              Option(request.remoteAddress).getOrElse("unknown"))
    }

    val source = request.getQueryString("zrc").flatMap(decodeSource) orElse request.getQueryString("source")

    loadFiddle(fiddleId, version.toInt, source).map {
      case Success(fd) =>
        val fdJson = write(fd)
        Ok(views.html.index("ScalaFiddle", fdJson)).withHeaders(CACHE_CONTROL -> "max-age=3600")
      case Failure(ex) =>
        NotFound
    }
  }

  def signOut = silhouette.SecuredAction.async { implicit request =>
    val result = Redirect(routes.Application.index("", "0"))
    silhouette.env.eventBus.publish(LogoutEvent(request.identity, request))
    silhouette.env.authenticatorService.discard(request.authenticator, result)
  }

  def rawFiddle(fiddleId: String, version: String) = Action.async { implicit request =>
    if (fiddleId.nonEmpty)
      persistence ! AddAccess(fiddleId,
                              version.toInt,
                              None,
                              embedded = true,
                              Option(request.remoteAddress).getOrElse("unknown"))

    loadFiddle(fiddleId, version.toInt).map {
      case Success(fd) =>
        // create a source code file for embedded ScalaFiddle
        val nameOpt = fd.name match {
          case empty if empty.replaceAll("\\s", "").isEmpty => None
          case nonEmpty                                     => Some(nonEmpty.replaceAll("\\s", " "))
        }
        val sourceCode = new StringBuilder()
        sourceCode.append(fd.sourceCode)
        val allLibs = fd.libraries.flatMap(lib => Library.stringify(lib) +: lib.extraDeps)
        sourceCode.append(allLibs.map(lib => s"// $$FiddleDependency $lib").mkString("\n", "\n", "\n"))
        nameOpt.foreach(name => sourceCode.append(s"// $$FiddleName $name\n"))

        Ok(sourceCode.toString).withHeaders(CACHE_CONTROL -> "max-age=3600")
      case Failure(ex) =>
        NotFound
    }
  }

  def libraryListing(scalaVersion: String) = Action {
    val libStrings = librarian.libraries
      .filter(_.scalaVersions.contains(scalaVersion))
      .flatMap(lib => Library.stringify(lib) +: lib.extraDeps)
    Ok(write(libStrings)).as("application/json").withHeaders(CACHE_CONTROL -> "max-age=60")
  }

  def resultFrame = Action { request =>
    Ok(views.html.resultframe()).withHeaders(CACHE_CONTROL -> "max-age=3600")
  }

  val loginProviders = config.getStringSeq("scalafiddle.loginProviders").get.map(AllLoginProviders.providers)

  def autowireApi(path: String) = silhouette.UserAwareAction.async { implicit request =>
    val apiService: Api = new ApiService(persistence, request.identity, loginProviders)

    // get the request body as JSON
    val b = request.body.asText.get

    // call Autowire route
    Router
      .route[Api](apiService)(
        autowire.Core.Request(path.split("/"), json.read(b).asInstanceOf[Js.Obj].value.toMap)
      )
      .map(buffer => {
        val data = json.write(buffer)
        Ok(data)
      })
  }

  def loadFiddle(id: String, version: Int, sourceOpt: Option[String] = None): Future[Try[FiddleData]] = {
    if (id == "") {
      val (source, libs) = parseFiddle(sourceOpt.fold(defaultSource)(identity))
      Future.successful(
        Success(
          FiddleData("",
            "",
            source,
            libs,
            librarian.libraries,
            config.getString("scalafiddle.defaultScalaVersion").get,
            None)))
    } else {
      ask(persistence, FindFiddle(id, version)).mapTo[Try[Fiddle]].flatMap {
        case Success(f) if f.user == "anonymous" =>
          Future.successful(
            Success(
              FiddleData(
                f.name,
                f.description,
                f.sourceCode,
                f.libraries.flatMap(librarian.findLibrary),
                librarian.libraries,
                f.scalaVersion,
                None
              )))
        case Success(f) =>
          ask(persistence, FindUser(f.user)).mapTo[Try[User]].map {
            case Success(u) =>
              val user = UserInfo(u.userID, u.name.getOrElse("Anonymous"), u.avatarURL, loggedIn = false)
              Success(
                FiddleData(
                  f.name,
                  f.description,
                  f.sourceCode,
                  f.libraries.flatMap(librarian.findLibrary),
                  librarian.libraries,
                  f.scalaVersion,
                  Some(user)
                ))
            case _ =>
              Success(
                FiddleData(
                  f.name,
                  f.description,
                  f.sourceCode,
                  f.libraries.flatMap(librarian.findLibrary),
                  librarian.libraries,
                  f.scalaVersion,
                  None
                ))
          }
        case Failure(e) =>
          Future.successful(Failure(e))
      }
    }
  }

  def parseFiddle(source: String): (String, Seq[Library]) = {
    val dependencyRE          = """ *// \$FiddleDependency (.+)""".r
    val lines                 = source.split("\n")
    val (libLines, codeLines) = lines.partition(line => dependencyRE.findFirstIn(line).isDefined)
    val libs                  = libLines.flatMap(line => librarian.findLibrary(dependencyRE.findFirstMatchIn(line).get.group(1)))
    (codeLines.mkString("\n"), libs)
  }

  def decodeSource(b64: String): Option[String] = {
    try {
      import com.github.marklister.base64.Base64._
      implicit def scheme: B64Scheme = base64Url
      // decode base64 and gzip
      val compressedSource = Decoder(b64).toByteArray
      val bis = new ByteArrayInputStream(compressedSource)
      val zis = new GZIPInputStream(bis)
      val buf = new Array[Byte](1024)
      val bos = new ByteArrayOutputStream()
      var len = 0
      while ( {len = zis.read(buf); len > 0}) {
        bos.write(buf, 0, len)
      }
      zis.close()
      bos.close()
      Some(new String(bos.toByteArray, StandardCharsets.UTF_8))
    } catch {
      case e: Throwable =>
        log.info(s"Invalid encoded source received: $e")
        None
    }
  }

  def createTables() = {
    log.debug(s"Creating missing tables")
    // create tables
    val dbConfig = DatabaseConfig.forConfig[JdbcProfile](config.getString("scalafiddle.dbConfig").get)
    val db       = dbConfig.db
    val dal      = new FiddleDAL(dbConfig.profile)
    import dal.driver.api._

    def createTableIfNotExists(tables: Seq[TableQuery[_ <: Table[_]]]): Future[Any] = {
      // create tables in order, waiting for previous "create" to complete before running next
      tables.foldLeft(Future.successful(())) { (f, table) =>
        f.flatMap(_ =>
          db.run(MTable.getTables(table.baseTableRow.tableName)).flatMap { result =>
            if (result.isEmpty) {
              log.debug(s"Creating table: ${table.baseTableRow.tableName}")
              db.run(table.schema.create)
            } else {
              Future.successful(())
            }
        })
      }
    }
    Await.result(createTableIfNotExists(Seq(dal.fiddles, dal.users, dal.accesses)), Duration.Inf)
  }
}
