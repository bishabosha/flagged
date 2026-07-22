package flagged.internal

import java.nio.file.Path
import java.time.{Instant, LocalDate, LocalDateTime, LocalTime}
import java.time.format.DateTimeParseException
import scala.concurrent.duration.FiniteDuration
import flagged.{Parser, Result}
import Result.eval

/** Value instances for types that are not available on every platform, mixed into the [[Parser]]
  * companion. The JVM has all of them.
  */
private[flagged] trait PlatformValues:

  given Parser.Value[FiniteDuration] = duration

  given Parser.Value[Path] = path

  private def temporal[A](name: String)(f: String => A): Parser.Value[A] =
    Parser.of(name): s =>
      Result:
        try f(s.trim)
        catch case _: DateTimeParseException => eval.raise(s"'$s' is not a valid $name")

  given Parser.Value[LocalDate]     = temporal("date")(LocalDate.parse)
  given Parser.Value[LocalTime]     = temporal("time")(LocalTime.parse)
  given Parser.Value[LocalDateTime] = temporal("date-time")(LocalDateTime.parse)
  given Parser.Value[Instant]       = temporal("instant")(Instant.parse)
