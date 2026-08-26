package com.experimentalneutron.olympus.application

import io.circe.{Json, JsonObject}
import org.yaml.snakeyaml.Yaml

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/**
 * The constellation manifest — codex's status-of-record, rendered by the Olympus board.
 *
 * This service does NOT own the manifest and never writes it. It is a read path, deliberately: the
 * board renders FROM the manifest so it cannot disagree with git. A board that owned state would
 * become a second source of truth.
 *
 * The source is a SEAM, not a hardcoded path. Today the manifest arrives as a mounted file (a
 * ConfigMap; codex is a private repo the pods cannot read). The intended end state is Iris — the
 * Obsidian vault gateway — serving every service's contracts, at which point this repoints via
 * config with no code change.
 */
object Constellation:

  /** Where the manifest comes from. */
  enum Source:
    /** A mounted file, e.g. a ConfigMap projection. */
    case File(path: Path)

    /** An HTTP source — Iris, once the contracts live in the vault. */
    case Http(url: String)

  final case class LoadFailure(message: String) extends RuntimeException(message)

  /**
   * `file:/etc/olympus/constellation.yaml` or `http(s)://…`. A bare path is treated as a file so
   * the common case stays readable in chart values.
   */
  def sourceFrom(raw: String): Source =
    val trimmed = raw.trim
    if trimmed.startsWith("http://") || trimmed.startsWith("https://") then Source.Http(trimmed)
    else if trimmed.startsWith("file:") then Source.File(Path.of(trimmed.stripPrefix("file:")))
    else Source.File(Path.of(trimmed))

  /** YAML in, JSON out — so neither the BFF nor the browser needs a YAML parser. */
  def parse(yaml: String): Json =
    val loaded =
      try new Yaml().load[Any](yaml)
      catch
        case NonFatal(e) => throw LoadFailure(s"constellation is not valid YAML: ${e.getMessage}")

    loaded match
      case null => throw LoadFailure("constellation is empty")
      case other =>
        toJson(other) match
          case j if j.isObject => j
          case _ => throw LoadFailure("constellation must be a YAML mapping at the top level")

  private def toJson(value: Any): Json = value match
    case null => Json.Null
    case b: java.lang.Boolean => Json.fromBoolean(b)
    case i: java.lang.Integer => Json.fromInt(i)
    case l: java.lang.Long => Json.fromLong(l)
    case d: java.lang.Double => Json.fromDoubleOrNull(d)
    case s: String => Json.fromString(s)
    case d: java.util.Date => Json.fromString(d.toInstant.toString)
    case l: java.util.List[?] => Json.fromValues(l.asScala.map(toJson).toVector)
    case m: java.util.Map[?, ?] =>
      Json.fromJsonObject(
        JsonObject.fromIterable(
          m.asScala.toVector.map { case (k, v) => String.valueOf(k) -> toJson(v) }
        )
      )
    case other => Json.fromString(String.valueOf(other))

  def readFile(path: Path): String =
    if !Files.exists(path) then
      throw LoadFailure(
        s"no constellation manifest at $path — mount it (ConfigMap) or point " +
          "OLYMPUS_CONSTELLATION_SOURCE somewhere else"
      )
    else
      try new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
      catch case NonFatal(e) => throw LoadFailure(s"cannot read $path: ${e.getMessage}")
