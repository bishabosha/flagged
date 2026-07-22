package flagged.internal

import java.nio.file.Path
import scala.concurrent.duration.FiniteDuration
import flagged.Parser

/** Value instances for types that are not available on every platform, mixed into the [[Parser]]
  * companion. Scala Native has `java.nio.file` but not `java.time`.
  */
private[flagged] trait PlatformValues:

  given Parser.Value[FiniteDuration] = duration

  given Parser.Value[Path] = path
