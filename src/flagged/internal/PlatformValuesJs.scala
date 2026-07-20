//> using target.platform scala-js
package flagged.internal

import scala.concurrent.duration.FiniteDuration
import flagged.Parser

/** Value instances for types that are not available on every platform, exported from the [[Parser]]
  * companion. Scala.js has neither `java.nio.file` nor `java.time`.
  */
object PlatformValues:

  given Parser.Value[FiniteDuration] = duration
