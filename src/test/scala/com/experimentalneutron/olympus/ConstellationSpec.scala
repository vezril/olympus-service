package com.experimentalneutron.olympus

import com.experimentalneutron.olympus.application.Constellation
import com.experimentalneutron.olympus.application.Constellation.Source
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Files, Path}

final class ConstellationSpec extends AnyWordSpec with Matchers:

  "sourceFrom" should {
    "treat http(s) as a fetch — the Iris path" in {
      Constellation.sourceFrom("https://iris.iris.svc.cluster.local/notes?path=x") shouldBe
        Source.Http("https://iris.iris.svc.cluster.local/notes?path=x")
      Constellation.sourceFrom("http://iris/notes") shouldBe Source.Http("http://iris/notes")
    }

    "treat file: and a bare path as a mounted file" in {
      Constellation.sourceFrom("file:/etc/olympus/constellation.yaml") shouldBe
        Source.File(Path.of("/etc/olympus/constellation.yaml"))
      Constellation.sourceFrom("  /etc/olympus/constellation.yaml  ") shouldBe
        Source.File(Path.of("/etc/olympus/constellation.yaml"))
    }
  }

  "parse" should {
    "turn YAML into JSON so no client needs a YAML parser" in {
      val json = Constellation.parse("""
        version: 1
        lifecycle: [live, building]
        services:
          - id: hermes
            status: live
            components:
              - repo: hermes-ui
                version: '0.1.8'
      """)
      val c = json.hcursor
      c.get[Int]("version").toOption shouldBe Some(1)
      c.downField("lifecycle").focus.flatMap(_.asArray).map(_.size) shouldBe Some(2)
      c.downField("services").downArray.get[String]("id").toOption shouldBe Some("hermes")
    }

    "reject a manifest that is not a mapping" in {
      an[Constellation.LoadFailure] should be thrownBy Constellation.parse("- just\n- a list")
    }

    "reject an empty manifest rather than serving nothing" in {
      an[Constellation.LoadFailure] should be thrownBy Constellation.parse("")
    }

    "reject invalid YAML with a reason" in {
      val e = intercept[Constellation.LoadFailure](Constellation.parse("a: [1, 2\nb: }"))
      e.getMessage should include("not valid YAML")
    }
  }

  "readFile" should {
    "say what to do when the manifest is not mounted" in {
      val e = intercept[Constellation.LoadFailure](
        Constellation.readFile(Path.of("/nonexistent/constellation.yaml"))
      )
      e.getMessage should include("no constellation manifest")
      e.getMessage should include("OLYMPUS_CONSTELLATION_SOURCE")
    }

    "read a real file" in {
      val tmp = Files.createTempFile("constellation", ".yaml")
      try
        Files.writeString(tmp, "version: 1\n")
        Constellation
          .parse(Constellation.readFile(tmp))
          .hcursor
          .get[Int]("version")
          .toOption shouldBe
          Some(1)
      finally Files.deleteIfExists(tmp)
    }
  }

  "the REAL codex manifest" should {
    // Guards the contract this service relays. If codex changes the shape the
    // board depends on, this fails here rather than as an empty board.
    val real = Path.of(System.getProperty("user.home"), "Code/codex/constellation.yaml")

    "parse and carry the fields the board renders" in {
      assume(Files.exists(real), "codex checkout not present — skipped")
      val c = Constellation.parse(Constellation.readFile(real)).hcursor

      c.get[Int]("version").toOption should not be empty
      for field <- List("lifecycle", "services", "threads", "speculative", "open_decisions") do
        withClue(s"'$field' missing from the manifest: ") {
          c.downField(field).focus.flatMap(_.asArray) should not be empty
        }

      val statuses =
        c.downField("services")
          .focus
          .flatMap(_.asArray)
          .toVector
          .flatten
          .flatMap(_.hcursor.get[String]("status").toOption)
      val columns =
        c.downField("lifecycle")
          .focus
          .flatMap(_.asArray)
          .toVector
          .flatten
          .flatMap(j => j.asString.orElse(j.hcursor.get[String]("id").toOption))

      withClue("every service status must name a lifecycle column: ") {
        statuses.toSet.diff(columns.toSet) shouldBe empty
      }
    }
  }
