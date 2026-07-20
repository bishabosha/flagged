//> using target.platform jvm
package flagged.internal

import java.nio.file.Path
import java.time.{Instant, LocalDate, LocalDateTime, LocalTime}
import java.time.format.DateTimeParseException
import scala.concurrent.duration.{Duration, FiniteDuration}
import flagged.{Parser, Ok, Err}

/** Value instances for types that are not available on every platform, exported from the [[Parser]]
  * companion. The JVM has all of them.
  */
object PlatformValues:

  given Parser.Value[FiniteDuration] = duration

  given Parser.Value[Path] = path

  private def temporal[A](name: String)(f: String => A): Parser.Value[A] =
    Parser.of(name)(s =>
      try Ok(f(s.trim))
      catch case _: DateTimeParseException => Err(s"'$s' is not a valid $name")
    )

  given Parser.Value[LocalDate]     = temporal("date")(LocalDate.parse)
  given Parser.Value[LocalTime]     = temporal("time")(LocalTime.parse)
  given Parser.Value[LocalDateTime] = temporal("date-time")(LocalDateTime.parse)
  given Parser.Value[Instant]       = temporal("instant")(Instant.parse)
