package com.experimentalneutron.olympus.application

import com.experimentalneutron.olympus.application.Constellation.Source
import io.circe.Json
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.HttpExt
import org.apache.pekko.http.scaladsl.model.{HttpRequest, StatusCodes}
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.apache.pekko.pattern.after

import java.util.concurrent.TimeoutException
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

/** The seam the route depends on, so it can be tested without a file or a network. */
trait ConstellationSource:
  def fetch(): Future[Json]

/**
 * Reads the manifest from wherever it currently lives.
 *
 * Not cached: the file case is a local read, and the HTTP case (Iris) is one small request per
 * board load. Add a TTL here if the board ever polls aggressively — this is the single place it
 * would go.
 */
final class ConstellationProvider(
    source: Source,
    http: HttpExt,
    timeout: FiniteDuration
)(using system: ActorSystem[?])
    extends ConstellationSource:

  private given ExecutionContext = system.executionContext

  override def fetch(): Future[Json] = source match
    case Source.File(path) =>
      Future(Constellation.parse(Constellation.readFile(path)))

    case Source.Http(url) =>
      val request = http.singleRequest(HttpRequest(uri = url))

      Future
        .firstCompletedOf(
          Seq(
            request,
            after(timeout, system.classicSystem.scheduler)(
              Future.failed(TimeoutException(s"$url did not answer in ${timeout.toMillis}ms"))
            )
          )
        )
        .flatMap { response =>
          if response.status == StatusCodes.OK then
            Unmarshal(response.entity).to[String].map(Constellation.parse)
          else
            response.entity.discardBytes().future().flatMap { _ =>
              Future.failed(
                Constellation.LoadFailure(s"$url answered HTTP ${response.status.intValue}")
              )
            }
        }
