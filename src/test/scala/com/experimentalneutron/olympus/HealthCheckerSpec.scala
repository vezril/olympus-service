package com.experimentalneutron.olympus

import com.experimentalneutron.olympus.application.HealthChecker
import com.experimentalneutron.olympus.domain.*
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.scalatest.{BeforeAndAfterAll, OptionValues}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * Probes a REAL server on an ephemeral port. Health checking is entirely about what happens on the
 * wire, so a mocked client would prove nothing.
 */
final class HealthCheckerSpec
    extends AnyWordSpec
    with Matchers
    with ScalaFutures
    with OptionValues
    with BeforeAndAfterAll:

  private val testKit = ActorTestKit()
  private given ActorSystem[?] = testKit.system

  override given patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(10, Seconds), interval = Span(50, Millis))

  private val route =
    concat(
      path("ok")(get(complete(StatusCodes.OK))),
      path("redirect")(get(complete(StatusCodes.Found))),
      path("boom")(get(complete(StatusCodes.InternalServerError))),
      path("missing")(get(complete(StatusCodes.NotFound))),
      path("slow")(get(complete {
        Thread.sleep(2000)
        StatusCodes.OK
      }))
    )

  private lazy val binding =
    Await.result(Http().newServerAt("127.0.0.1", 0).bind(route), 10.seconds)

  private lazy val port = binding.localAddress.getPort

  override def beforeAll(): Unit = { val _ = port }

  override def afterAll(): Unit =
    Await.result(binding.unbind(), 10.seconds)
    testKit.shutdownTestKit()

  private def entry(id: String, path: String, status: ConsoleStatus = ConsoleStatus.Live) =
    ConsoleEntry(
      id = id,
      name = id.capitalize,
      blurb = "test",
      href = s"https://$id.example",
      namespace = id,
      service = s"$id-ui",
      accent = "x",
      accentAlt = None,
      status = status,
      port = 80,
      healthUrl = s"http://127.0.0.1:$port/$path"
    )

  private def checker(timeout: FiniteDuration = 3.seconds) =
    HealthChecker(Http(), timeout)

  "HealthChecker.check" should {

    "report live on a 200, with a latency" in {
      val result = checker().check(entry("hermes", "ok")).futureValue
      result.state shouldBe HealthState.Live
      result.httpStatus shouldBe Some(200)
      result.latencyMs.value should be >= 0L
      result.error shouldBe None
    }

    "treat a redirect as live — 200-399 passes, like the k8s probe" in {
      checker().check(entry("hermes", "redirect")).futureValue.state shouldBe HealthState.Live
    }

    "report down with the status on a 500" in {
      val result = checker().check(entry("hermes", "boom")).futureValue
      result.state shouldBe HealthState.Down
      result.error shouldBe Some("HTTP 500")
    }

    "report down on a 404" in {
      checker().check(entry("hermes", "missing")).futureValue.error shouldBe Some("HTTP 404")
    }

    "report down with 'timed out' when the console does not answer in budget" in {
      val result = checker(300.millis).check(entry("hermes", "slow")).futureValue
      result.state shouldBe HealthState.Down
      result.error shouldBe Some("timed out")
    }

    "report down with a reason when the connection is refused" in {
      val dead = entry("hermes", "ok").copy(healthUrl = "http://127.0.0.1:1/")
      val result = checker(2.seconds).check(dead).futureValue
      result.state shouldBe HealthState.Down
      result.error should not be empty
    }

    "never probe a planned console" in {
      // Points at a port nothing listens on: if it were probed, this would be Down.
      val planned =
        entry("hera", "ok", ConsoleStatus.Planned).copy(healthUrl = "http://127.0.0.1:1/")
      val result = checker().check(planned).futureValue
      result.state shouldBe HealthState.Planned
      result.latencyMs shouldBe None
    }
  }

  "HealthChecker.checkAll" should {

    "not let one dead console take down the report" in {
      val entries = List(
        entry("hermes", "ok"),
        entry("apollo", "boom"),
        entry("hera", "ok", ConsoleStatus.Planned)
      )
      val report = checker().checkAll(entries).futureValue
      val byId = report.results.map(r => r.id -> r.state).toMap

      byId("hermes") shouldBe HealthState.Live
      byId("apollo") shouldBe HealthState.Down
      byId("hera") shouldBe HealthState.Planned
      report.results should have size 3
    }

    "stamp the report with an ISO instant" in {
      val report = checker().checkAll(List(entry("hermes", "ok"))).futureValue
      noException should be thrownBy java.time.Instant.parse(report.checkedAt)
    }
  }
