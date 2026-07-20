//> using target.platform scala-native
package flagged.internal

import java.nio.file.Path
import scala.concurrent.duration.FiniteDuration
import flagged.Parser

/** Value instances for types that are not available on every platform, exported from the [[Parser]]
  * companion. Scala Native has `java.nio.file` but not `java.time`.
  */
object PlatformValues:

  given Parser.Value[FiniteDuration] = duration

  given Parser.Value[Path] = path
