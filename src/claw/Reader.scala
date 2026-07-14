package claw

import java.io.File
import java.nio.file.{InvalidPathException, Path, Paths}
import java.time.{Instant, LocalDate, LocalDateTime, LocalTime}
import java.time.format.DateTimeParseException
import java.util.UUID
import scala.concurrent.duration.{Duration, FiniteDuration}

/** Parses a single command-line token into a value of type `A`.
  *
  * `typeName` is used as the value placeholder in help output, e.g. `--depth <int>`.
  */
trait Reader[A]:
  self =>
  def typeName: String
  def read(s: String): Either[String, A]

  final def map[B](f: A => B): Reader[B] = new Reader[B]:
    def typeName = self.typeName
    def read(s: String) = self.read(s).map(f)

  final def emap[B](f: A => Either[String, B]): Reader[B] = new Reader[B]:
    def typeName = self.typeName
    def read(s: String) = self.read(s).flatMap(f)

  final def withTypeName(name: String): Reader[A] = new Reader[A]:
    def typeName = name
    def read(s: String) = self.read(s)

object Reader:
  def apply[A](using r: Reader[A]): Reader[A] = r

  /** Build a reader from a name and a parse function. */
  def of[A](name: String)(f: String => Either[String, A]): Reader[A] = new Reader[A]:
    def typeName = name
    def read(s: String) = f(s)

  private def numeric[A](name: String)(f: String => A): Reader[A] =
    of(name)(s =>
      try Right(f(s.trim))
      catch case _: NumberFormatException => Left(s"'$s' is not a valid $name")
    )

  given Reader[String]     = of("string")(Right(_))
  given Reader[Int]        = numeric("int")(_.toInt)
  given Reader[Long]       = numeric("long")(_.toLong)
  given Reader[Short]      = numeric("short")(_.toShort)
  given Reader[Byte]       = numeric("byte")(_.toByte)
  given Reader[Float]      = numeric("float")(_.toFloat)
  given Reader[Double]     = numeric("double")(_.toDouble)
  given Reader[BigInt]     = numeric("integer")(BigInt(_))
  given Reader[BigDecimal] = numeric("decimal")(BigDecimal(_))

  given Reader[Boolean] = of("bool")(internal.Runtime.parseBool)

  given Reader[Char] = of("char")(s =>
    if s.length == 1 then Right(s.charAt(0)) else Left(s"'$s' is not a single character")
  )

  given Reader[Path] = of("path")(s =>
    try Right(Paths.get(s))
    catch case e: InvalidPathException => Left(s"'$s' is not a valid path")
  )

  given Reader[File] = of("file")(s => Right(new File(s)))

  given Reader[UUID] = of("uuid")(s =>
    try Right(UUID.fromString(s.trim))
    catch case _: IllegalArgumentException => Left(s"'$s' is not a valid UUID")
  )

  private def temporal[A](name: String)(f: String => A): Reader[A] =
    of(name)(s =>
      try Right(f(s.trim))
      catch case _: DateTimeParseException => Left(s"'$s' is not a valid $name")
    )

  given Reader[LocalDate]     = temporal("date")(LocalDate.parse)
  given Reader[LocalTime]     = temporal("time")(LocalTime.parse)
  given Reader[LocalDateTime] = temporal("date-time")(LocalDateTime.parse)
  given Reader[Instant]       = temporal("instant")(Instant.parse)

  given Reader[FiniteDuration] = of("duration")(s =>
    try
      Duration(s.trim) match
        case fd: FiniteDuration => Right(fd)
        case _                  => Left(s"'$s' is not a finite duration")
    catch case _: NumberFormatException => Left(s"'$s' is not a valid duration (try e.g. '30s' or '5.minutes')")
  )

  /** Derive a reader for an enum (or sealed trait) whose cases are all parameterless:
    * values are matched by kebab-cased case name, case-insensitively.
    * Enables `enum Color derives Reader` and thus `--color red`.
    */
  inline def derived[A]: Reader[A] = ${ internal.ParserMacros.deriveReader[A] }
