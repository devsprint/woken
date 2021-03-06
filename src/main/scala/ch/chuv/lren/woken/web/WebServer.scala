/*
 * Copyright (C) 2017  LREN CHUV for Human Brain Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ch.chuv.lren.woken.web

import akka.http.scaladsl.Http
import cats.effect._
import ch.chuv.lren.woken.akka.{ AkkaServer, CoreSystem }
import ch.chuv.lren.woken.api.Api
import ch.chuv.lren.woken.api.ssl.WokenSSLConfiguration
import ch.chuv.lren.woken.config.WokenConfiguration
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import scala.language.higherKinds
import scala.util.{ Failure, Success }

class WebServer[F[_]: ConcurrentEffect: Timer](override val core: CoreSystem,
                                               override val config: WokenConfiguration)
    extends Api
    with StaticResources
    with WokenSSLConfiguration {

  import WebServer.logger

  val binding: Future[Http.ServerBinding] = {
    import core._
    val http = Http()
    val app  = config.app

    if (app.webServicesHttps) http.setDefaultServerHttpContext(https)

    logger.info(
      s"Start Web server on http${if (app.webServicesHttps) "s" else ""}://${app.networkInterface}:${app.webServicesPort}"
    )

    // Start a new HTTP server on port 8080 with our service actor as the handler
    http.bindAndHandle(
      routes,
      interface = app.networkInterface,
      port = app.webServicesPort
    )
  }

  def selfChecks(): Unit = {
    logger.info("Self checks...")

    val b = Await.result(binding, 30.seconds)

    // TODO: add more self checks for Web server
    // TODO: it should fail fast if an error is detected

    logger.info(s"[OK] Web server is running on ${b.localAddress}")
  }

  def unbind(): F[Unit] = {
    import core._

    Sync[F].defer {
      logger.warn("Stopping here", new Exception())

      logger.info(s"Shutdown Web server")

      // Attempt to leave the cluster before shutting down
      val serverShutdown = binding
        .flatMap(_.unbind())
      Async[F].async { cb =>
        serverShutdown.onComplete {
          case Success(_)     => cb(Right(()))
          case Failure(error) => cb(Left(error))
        }
      }
    }
  }
}

object WebServer {

  private val logger: Logger = Logger(LoggerFactory.getLogger("woken.WebServer"))

  /** Resource that creates and yields a web server, guaranteeing cleanup. */
  def resource[F[_]: ConcurrentEffect: ContextShift: Timer](
      akkaServer: AkkaServer[F],
      config: WokenConfiguration
  ): Resource[F, WebServer[F]] =
    // start a new HTTP server with our service actor as the handler
    Resource.make(Sync[F].delay {
      logger.info(s"Start web server on port ${config.app.webServicesPort}")
      val server = new WebServer(akkaServer, config)

      server.selfChecks()

      server
    })(_.unbind())

}
