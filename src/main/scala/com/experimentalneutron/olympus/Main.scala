package com.experimentalneutron.olympus

import com.experimentalneutron.olympus.api.Routes
import com.experimentalneutron.olympus.application.{
  Constellation,
  ConstellationProvider,
  HealthChecker,
  Registry
}
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.Http
import org.slf4j.LoggerFactory

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success}

object Main:

  private val log = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit =
    val config = ConfigFactory.load()

    given system: ActorSystem[Nothing] =
      ActorSystem(Behaviors.empty, "olympus", config)

    import system.executionContext

    val consoles = Registry.load(config)
    val timeout = FiniteDuration(
      config.getDuration("olympus.health.timeout").toMillis,
      TimeUnit.MILLISECONDS
    )

    val version = Option(getClass.getPackage.getImplementationVersion).getOrElse("dev")
    val checker = HealthChecker(Http(), timeout)

    // file today (a mounted ConfigMap — codex is private and the pods cannot read
    // it); Iris once the contracts live in the vault. Config, not code.
    val constellation = ConstellationProvider(
      Constellation.sourceFrom(config.getString("olympus.constellation.source")),
      Http(),
      timeout
    )

    val routes = Routes(consoles, checker, constellation, version).routes

    val host = config.getString("olympus.http.host")
    val port = config.getInt("olympus.http.port")

    Http()
      .newServerAt(host, port)
      .bind(routes)
      .onComplete {
        case Success(binding) =>
          val addr = binding.localAddress
          log.info(
            "olympus-service {} listening on {}:{} — {} consoles, {}ms probe budget",
            version,
            addr.getHostString,
            addr.getPort,
            consoles.size,
            timeout.toMillis
          )
        case Failure(e) =>
          log.error(s"could not bind to $host:$port", e)
          system.terminate()
      }
