package com.experimentalneutron.olympus

import com.experimentalneutron.olympus.api.Routes
import com.experimentalneutron.olympus.application.ConsoleHealth
import com.experimentalneutron.olympus.domain.*
import io.circe.parser.parse
import org.apache.pekko.http.scaladsl.model.{ContentTypes, StatusCodes}
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import scala.concurrent.Future

final class RoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest:

  private val consoles = List(
    ConsoleEntry(
      id = "hermes",
      name = "Hermes",
      blurb = "Messaging and delivery.",
      href = "https://hermes.home.experimentalneutron.com",
      namespace = "hermes",
      service = "hermes-ui",
      accent = "oklch(0.8 0.25 145)",
      accentAlt = None,
      status = ConsoleStatus.Live,
      healthUrl = "http://hermes-ui.hermes.svc.cluster.local/"
    ),
    ConsoleEntry(
      id = "hera",
      name = "Hera",
      blurb = "Not yet built.",
      href = "https://hera.home.experimentalneutron.com",
      namespace = "hera",
      service = "hera-ui",
      accent = "oklch(0.78 0.1 25)",
      accentAlt = None,
      status = ConsoleStatus.Planned,
      healthUrl = "http://hera-ui.hera.svc.cluster.local/"
    )
  )

  private val stubHealth = new ConsoleHealth:
    override def checkAll(entries: List[ConsoleEntry]): Future[HealthReport] =
      Future.successful(
        HealthReport(
          Instant.parse("2026-08-25T12:00:00Z").toString,
          List(
            HealthResult("hermes", HealthState.Live, Some(200), Some(12)),
            HealthResult("hera", HealthState.Planned)
          )
        )
      )

  private val routes = Routes(consoles, stubHealth, "1.2.3").routes

  "GET /health" should {
    "answer with this service's own liveness" in {
      Get("/health") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        contentType shouldBe ContentTypes.`application/json`
        val json = parse(responseAs[String]).toOption.get.hcursor
        json.get[String]("status").toOption shouldBe Some("ok")
        json.get[String]("service").toOption shouldBe Some("olympus-service")
        json.get[String]("version").toOption shouldBe Some("1.2.3")
        json.get[Int]("consoles").toOption shouldBe Some(2)
      }
    }
  }

  "GET /consoles" should {
    "return the registry" in {
      Get("/consoles") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val entries = parse(responseAs[String]).toOption.get.asArray.get
        entries should have size 2
        entries.head.hcursor.get[String]("id").toOption shouldBe Some("hermes")
        entries.head.hcursor.get[String]("status").toOption shouldBe Some("live")
      }
    }

    "omit absent optional fields rather than sending null" in {
      Get("/consoles") ~> routes ~> check {
        val first = parse(responseAs[String]).toOption.get.asArray.get.head
        first.hcursor.keys.get should not contain "accentAlt"
      }
    }

    "not leak the in-cluster probe target to the browser" in {
      // healthUrl IS serialised (it is part of the entry), so assert deliberately:
      // if this ever needs hiding, this test is the place it gets decided.
      Get("/consoles") ~> routes ~> check {
        responseAs[String] should include("svc.cluster.local")
      }
    }
  }

  "GET /health/consoles" should {
    "return the aggregated report and forbid caching" in {
      Get("/health/consoles") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        header("Cache-Control").map(_.value) shouldBe Some("no-store")

        val json = parse(responseAs[String]).toOption.get.hcursor
        json.get[String]("checkedAt").toOption shouldBe Some("2026-08-25T12:00:00Z")

        val results = json.downField("results").focus.get.asArray.get
        results should have size 2
        results.head.hcursor.get[String]("state").toOption shouldBe Some("live")
        results(1).hcursor.get[String]("state").toOption shouldBe Some("planned")
      }
    }

    "not be shadowed by /health" in {
      Get("/health/consoles") ~> routes ~> check {
        responseAs[String] should include("results")
      }
    }
  }

  "GET /" should {
    "redirect to /health so a root readiness probe stays honest" in {
      Get("/") ~> routes ~> check {
        status shouldBe StatusCodes.TemporaryRedirect
        header("Location").map(_.value) shouldBe Some("/health")
      }
    }
  }
