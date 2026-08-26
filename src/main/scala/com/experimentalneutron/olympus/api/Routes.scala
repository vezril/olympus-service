package com.experimentalneutron.olympus.api

import com.experimentalneutron.olympus.application.ConsoleHealth
import com.experimentalneutron.olympus.domain.ConsoleEntry
import io.circe.Json
import io.circe.syntax.*
import org.apache.pekko.http.scaladsl.model.headers.`Cache-Control`
import org.apache.pekko.http.scaladsl.model.headers.CacheDirectives.`no-store`
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route

/**
 * The HTTP edge.
 *
 * GET /health this service's own liveness — what the k8s probe hits GET /consoles the registry GET
 * /health/consoles the aggregated fan-out the portal renders
 */
final class Routes(consoles: List[ConsoleEntry], checker: ConsoleHealth, version: String):

  import JsonSupport.given

  val routes: Route =
    concat(
      // Must be declared before /health — a prefix match would shadow it.
      path("health" / "consoles") {
        get {
          respondWithHeader(`Cache-Control`(`no-store`)) {
            complete(checker.checkAll(consoles))
          }
        }
      },
      path("health") {
        get {
          complete(
            HttpEntity(
              ContentTypes.`application/json`,
              JsonSupport.render(
                Json.obj(
                  "status" -> "ok".asJson,
                  "service" -> "olympus-service".asJson,
                  "version" -> version.asJson,
                  "consoles" -> consoles.size.asJson
                )
              )
            )
          )
        }
      },
      path("consoles") {
        get {
          complete(consoles)
        }
      },
      pathSingleSlash {
        get {
          // A root that answers keeps a GET / readiness probe honest.
          redirect("/health", StatusCodes.TemporaryRedirect)
        }
      }
    )
