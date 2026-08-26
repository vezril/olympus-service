package com.experimentalneutron.olympus.application

import com.experimentalneutron.olympus.domain.*
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.HttpExt
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}
import org.apache.pekko.pattern.after

import java.time.{Clock, Instant}
import java.util.concurrent.TimeoutException
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/**
 * Probes each live console and aggregates the answers.
 *
 * Two rules the portal depends on:
 *   - a dead console is DATA, never a failed report. One unreachable console must not take the
 *     whole fan-out down with it.
 *   - `Planned` consoles are never probed at all.
 */
/** The seam the routes depend on, so they can be tested without a network. */
trait ConsoleHealth:
  def checkAll(entries: List[ConsoleEntry]): Future[HealthReport]

final class HealthChecker(
    http: HttpExt,
    timeout: FiniteDuration,
    clock: Clock = Clock.systemUTC()
)(using system: ActorSystem[?])
    extends ConsoleHealth:

  private given ExecutionContext = system.executionContext

  def check(entry: ConsoleEntry): Future[HealthResult] =
    if entry.status == ConsoleStatus.Planned then
      Future.successful(HealthResult(entry.id, HealthState.Planned))
    else
      val started = System.nanoTime()

      def elapsedMs: Long = (System.nanoTime() - started) / 1000000L

      val request = HttpRequest(uri = entry.healthUrl)
        .withHeaders(RawHeader("user-agent", "olympus-service/health"))

      val probe: Future[HttpResponse] = http.singleRequest(request)

      val timed = Future.firstCompletedOf(
        Seq(
          probe,
          after(timeout, system.classicSystem.scheduler)(
            Future.failed(TimeoutException(s"no answer in ${timeout.toMillis}ms"))
          )
        )
      )

      timed
        .flatMap { response =>
          // Always drain the entity: an undrained response starves the pool.
          response.entity.discardBytes().future().map(_ => response.status.intValue)
        }
        .map { status =>
          if Health.isHealthyStatus(status) then
            HealthResult(entry.id, HealthState.Live, Some(status), Some(elapsedMs))
          else
            HealthResult(
              entry.id,
              HealthState.Down,
              Some(status),
              Some(elapsedMs),
              Some(s"HTTP $status")
            )
        }
        .recover {
          case _: TimeoutException =>
            HealthResult(entry.id, HealthState.Down, None, Some(elapsedMs), Some("timed out"))
          case NonFatal(e) =>
            HealthResult(entry.id, HealthState.Down, None, Some(elapsedMs), Some(describe(e)))
        }

  override def checkAll(entries: List[ConsoleEntry]): Future[HealthReport] =
    Future
      .traverse(entries)(check)
      .map(results => HealthReport(Instant.now(clock).toString, results))

  private def describe(e: Throwable): String =
    Option(e.getCause)
      .map(_.getMessage)
      .orElse(Option(e.getMessage))
      .map(_.takeWhile(_ != '\n'))
      .filter(_.nonEmpty)
      .getOrElse("unreachable")
