package flagged.internal

import scala.concurrent.duration.FiniteDuration
import flagged.Parser

/** Value instances for types that are not available on every platform, mixed into the [[Parser]]
  * companion. Scala.js has neither `java.nio.file` nor `java.time`.
  */
private[flagged] trait PlatformValues:

  given Parser.Value[FiniteDuration] = duration
