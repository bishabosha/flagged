package flagged.internal

import scala.concurrent.duration.{Duration, FiniteDuration}
import flagged.{Parser, Ok, Err}

/** The duration instance is portable; defined once here and re-exposed by each platform's
  * [[PlatformValues]].
  */
private[internal] def duration: Parser.Value[FiniteDuration] = Parser.of("duration")(s =>
  try
    Duration(s.trim) match
      case fd: FiniteDuration => Ok(fd)
      case _                  => Err(s"'$s' is not a finite duration")
  catch
    case _: NumberFormatException =>
      Err(s"'$s' is not a valid duration (try e.g. '30s' or '5.minutes')")
)
