package com.experimentalneutron.olympus.api

import com.experimentalneutron.olympus.domain.*
import io.circe.generic.semiauto.deriveEncoder
import io.circe.{Encoder, Json}
import org.apache.pekko.http.scaladsl.marshalling.{Marshaller, ToEntityMarshaller}
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity}

/** circe encoders plus the one marshaller that turns any of them into a response. */
object JsonSupport:

  given Encoder[ConsoleStatus] =
    Encoder.encodeString.contramap(s => ConsoleStatus.render(s))

  given Encoder[HealthState] =
    Encoder.encodeString.contramap(s => HealthState.render(s))

  given Encoder[ConsoleEntry] = deriveEncoder

  // Explicit: encodeList and encodeSeq both match, and the ambiguity blocks the
  // marshaller's implicit search at the call site.
  given Encoder[List[ConsoleEntry]] = Encoder.encodeList[ConsoleEntry]
  given Encoder[HealthResult] = deriveEncoder
  given Encoder[HealthReport] = deriveEncoder

  /** Absent fields are omitted, not sent as null — the client checks presence. */
  given jsonMarshaller[A](using encoder: Encoder[A]): ToEntityMarshaller[A] =
    Marshaller.withFixedContentType(ContentTypes.`application/json`) { value =>
      HttpEntity(ContentTypes.`application/json`, render(encoder(value)))
    }

  def render(json: Json): String = json.deepDropNullValues.noSpaces
