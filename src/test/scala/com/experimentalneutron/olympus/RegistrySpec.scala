package com.experimentalneutron.olympus

import com.experimentalneutron.olympus.application.Registry
import com.experimentalneutron.olympus.domain.ConsoleStatus
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class RegistrySpec extends AnyWordSpec with Matchers:

  private def configOf(consoles: String) =
    ConfigFactory.parseString(s"""
      olympus.domain = "home.experimentalneutron.com"
      olympus.consoles = [$consoles]
    """)

  private val hermes =
    """{ id = "hermes", name = "Hermes", blurb = "Messaging.",
         namespace = "hermes", service = "hermes-ui",
         accent = "oklch(0.8 0.25 145)", status = "live" }"""

  "Registry.load" should {

    "load the shipped registry" in {
      val entries = Registry.load(ConfigFactory.load())
      entries.map(_.id) should contain allOf (
        "dionysus",
        "hermes",
        "apollo",
        "artemis",
        "demeter",
        "hephaestus"
      )
      entries.count(_.status == ConsoleStatus.Planned) shouldBe 3
    }

    "ship probe targets that match the live cluster, not the god's name" in {
      // These were guessed as <god>-ui.<god> and were wrong for four of five
      // consoles — the portal reported everything Down. Verified against
      // `kubectl get svc` 2026-08-26; update here when a console moves.
      val byId = Registry.load(ConfigFactory.load()).map(e => e.id -> e.healthUrl).toMap

      byId("dionysus") shouldBe "http://dionysus-planner.default.svc.cluster.local:3000/"
      byId("hermes") shouldBe "http://hermes-hermes.hermes-ui.svc.cluster.local:80/"
      byId("apollo") shouldBe "http://apollo.apollo-ui.svc.cluster.local:80/"
      byId("artemis") shouldBe "http://artemis-ui-artemis-ui.artemis-ui.svc.cluster.local:80/"
      byId("demeter") shouldBe "http://demeter-ui.demeter.svc.cluster.local:3000/"
      byId("hephaestus") shouldBe "http://hephaestus-ui.hephaestus-ui.svc.cluster.local:80/"
    }

    "derive href from the id and domain" in {
      val entry = Registry.load(configOf(hermes)).head
      entry.href shouldBe "https://hermes.home.experimentalneutron.com"
    }

    "default the probe to GET / on the in-cluster Service, port included" in {
      val entry = Registry.load(configOf(hermes)).head
      entry.healthUrl shouldBe "http://hermes-ui.hermes.svc.cluster.local:80/"
      entry.port shouldBe 80
    }

    "carry a non-80 port into the default probe" in {
      val onThreeThousand =
        """{ id = "demeter", name = "Demeter", blurb = "Yields.",
             namespace = "demeter", service = "demeter-ui", port = 3000,
             accent = "x", status = "live" }"""
      val entry = Registry.load(configOf(onThreeThousand)).head
      entry.port shouldBe 3000
      entry.healthUrl shouldBe "http://demeter-ui.demeter.svc.cluster.local:3000/"
    }

    "prefer an explicit health-url" in {
      val withUrl =
        """{ id = "hermes", name = "Hermes", blurb = "Messaging.",
             namespace = "hermes", service = "hermes-ui",
             accent = "x", status = "live",
             health-url = "http://hermes-ui.hermes.svc.cluster.local/api/hermes/health" }"""
      Registry.load(configOf(withUrl)).head.healthUrl shouldBe
        "http://hermes-ui.hermes.svc.cluster.local/api/hermes/health"
    }

    "treat a blank override as absent rather than probing an empty URL" in {
      val blank =
        """{ id = "hermes", name = "Hermes", blurb = "Messaging.",
             namespace = "hermes", service = "hermes-ui",
             accent = "x", status = "live", health-url = "  " }"""
      Registry.load(configOf(blank)).head.healthUrl shouldBe
        "http://hermes-ui.hermes.svc.cluster.local:80/"
    }

    "fail loudly on a duplicate id" in {
      val e = intercept[Registry.LoadFailure](Registry.load(configOf(s"$hermes, $hermes")))
      e.getMessage should include("duplicate console ids: hermes")
    }

    "fail loudly on an unknown status" in {
      val bad =
        """{ id = "hermes", name = "Hermes", blurb = "x",
             namespace = "hermes", service = "hermes-ui",
             accent = "x", status = "maybe" }"""
      val e = intercept[Registry.LoadFailure](Registry.load(configOf(bad)))
      e.getMessage should include("unknown console status 'maybe'")
    }

    "fail loudly on a missing field, naming it" in {
      val bad = """{ id = "hermes", name = "Hermes", namespace = "hermes",
                     service = "hermes-ui", accent = "x", status = "live" }"""
      val e = intercept[Registry.LoadFailure](Registry.load(configOf(bad)))
      e.getMessage should include("missing 'blurb'")
    }

    "refuse an empty registry — the portal would have nothing to show" in {
      val e = intercept[Registry.LoadFailure](Registry.load(configOf("")))
      e.getMessage should include("empty")
    }
  }
