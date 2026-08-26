package com.experimentalneutron.olympus.domain

/** What a console is to Olympus. The registry is the source of truth for this. */
final case class ConsoleEntry(
    id: String,
    name: String,
    blurb: String,
    href: String,
    namespace: String,
    service: String,
    accent: String,
    accentAlt: Option[String],
    status: ConsoleStatus,
    /** In-cluster probe target. Defaults to GET / on the console's Service. */
    healthUrl: String
)

enum ConsoleStatus:
  case Live, Planned

object ConsoleStatus:
  def parse(raw: String): Either[String, ConsoleStatus] =
    raw.trim.toLowerCase match
      case "live" => Right(Live)
      case "planned" => Right(Planned)
      case other => Left(s"unknown console status '$other' (expected live or planned)")

  extension (s: ConsoleStatus)
    def render: String = s match
      case Live => "live"
      case Planned => "planned"

/**
 * The outcome of one probe. `Planned` consoles are never probed — they are named, not built, and a
 * permanent red pill would be a lie.
 */
enum HealthState:
  case Live, Down, Planned

object HealthState:
  extension (s: HealthState)
    def render: String = s match
      case Live => "live"
      case Down => "down"
      case Planned => "planned"

final case class HealthResult(
    id: String,
    state: HealthState,
    httpStatus: Option[Int] = None,
    latencyMs: Option[Long] = None,
    error: Option[String] = None
)

final case class HealthReport(checkedAt: String, results: List[HealthResult])

object Health:
  /** 200-399 passes, matching the k8s readiness-probe convention. */
  def isHealthyStatus(status: Int): Boolean = status >= 200 && status < 400
