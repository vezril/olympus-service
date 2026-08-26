package com.experimentalneutron.olympus.application

import com.experimentalneutron.olympus.domain.{ConsoleEntry, ConsoleStatus}
import com.typesafe.config.Config

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/**
 * Loads the console registry from config. Static today — the codex apps/ dir is what it mirrors. If
 * Olympus ever reads live from k8s, this is the seam.
 *
 * Loading is strict on purpose: a typo in a console entry should fail the service at startup, not
 * surface later as a console that silently vanished from the portal.
 */
object Registry:

  final case class LoadFailure(message: String) extends RuntimeException(message)

  def load(config: Config): List[ConsoleEntry] =
    val domain = config.getString("olympus.domain")
    val raw = config.getConfigList("olympus.consoles").asScala.toList

    if raw.isEmpty then
      throw LoadFailure("olympus.consoles is empty — the portal would have nothing to show")

    val entries = raw.zipWithIndex.map { case (c, i) => parse(c, i, domain) }

    val duplicates = entries.groupBy(_.id).collect { case (id, es) if es.sizeIs > 1 => id }
    if duplicates.nonEmpty then
      throw LoadFailure(s"duplicate console ids: ${duplicates.toList.sorted.mkString(", ")}")

    entries

  private def parse(c: Config, index: Int, domain: String): ConsoleEntry =
    def required(path: String): String =
      try c.getString(path)
      catch case NonFatal(_) => throw LoadFailure(s"olympus.consoles[$index] is missing '$path'")

    def optional(path: String): Option[String] =
      if c.hasPath(path) then Option(c.getString(path)).map(_.trim).filter(_.nonEmpty) else None

    val id = required("id")
    val port = if c.hasPath("port") then c.getInt("port") else 80
    val status = ConsoleStatus
      .parse(required("status"))
      .fold(msg => throw LoadFailure(s"olympus.consoles[$index] ($id): $msg"), identity)

    ConsoleEntry(
      id = id,
      name = required("name"),
      blurb = required("blurb"),
      href = optional("href").getOrElse(s"https://$id.$domain"),
      namespace = required("namespace"),
      service = required("service"),
      accent = required("accent"),
      accentAlt = optional("accent-alt"),
      status = status,
      port = port,
      // Port is explicit in the default: omitting it silently means 80, which
      // is how a console on 3000 ends up reported Down forever.
      healthUrl = optional("health-url")
        .getOrElse(
          s"http://${required("service")}.${required("namespace")}.svc.cluster.local:$port/"
        )
    )
