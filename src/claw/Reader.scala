package claw

import java.io.File
import java.nio.file.{InvalidPathException, Path, Paths}
import java.time.{Instant, LocalDate, LocalDateTime, LocalTime}
import java.time.format.DateTimeParseException
import java.util.UUID
import scala.concurrent.duration.{Duration, FiniteDuration}

/** Describes how command-line tokens become an `A`. The underlying [[Reader.Schema]]
  * encodes the *shape*: a `Value` reader parses one token per occurrence; a `Repeated`
  * reader accumulates every occurrence with an element reader and combines them, which
  * is how collection types (and anything else that wants repetition) opt in.
  */
@scala.annotation.implicitNotFound(
  "No given Reader[${A}] found.\n" +
    "Provide one with Reader.of / Reader.repeated, or add `derives Reader` to a parameterless enum.\n" +
    "If this field should be nested subcommands instead, add `derives Parser` to its type."
)
trait Reader[A]:
  self =>
  def schema: Reader.Schema[A]

  /** The help metavar: the value's type name, or the element's for repeated readers. */
  final def typeName: String = schema match
    case Reader.Schema.Value(name, _)        => name
    case Reader.Schema.Repeated(element, _)  => element.typeName

  /** Parse a single occurrence (for repeated readers: one element, then combine). */
  final def read(s: String): Result[A, String] = schema match
    case Reader.Schema.Value(_, parse)       => parse(s)
    case Reader.Schema.Repeated(element, build) => element.read(s).flatMap(e => build(List(e)))

  final def map[B](f: A => B): Reader[B] = emap(a => Ok(f(a)))

  final def emap[B](f: A => Result[B, String]): Reader[B] = Reader.fromSchema:
    schema match
      case Reader.Schema.Value(name, parse)       => Reader.Schema.Value(name, s => parse(s).flatMap(f))
      case Reader.Schema.Repeated(element, build) => Reader.Schema.Repeated(element, l => build(l).flatMap(f))

  final def withTypeName(name: String): Reader[A] = Reader.fromSchema:
    schema match
      case Reader.Schema.Value(_, parse)          => Reader.Schema.Value(name, parse)
      case Reader.Schema.Repeated(element, build) => Reader.Schema.Repeated(element.withTypeName(name), build)

object Reader:
  def apply[A](using r: Reader[A]): Reader[A] = r

  /** The shape of a reader. */
  enum Schema[A]:
    /** One token per occurrence. */
    case Value[T](typeName: String, parse: String => Result[T, String]) extends Schema[T]

    /** Any number of occurrences, each parsed by `element`, combined with `build`
      * (also invoked with `Nil` when the argument is absent — return `Err` to require
      * at least one occurrence).
      */
    case Repeated[E, T](element: Reader[E], build: List[E] => Result[T, String]) extends Schema[T]

  def fromSchema[A](s: Schema[A]): Reader[A] = new Reader[A]:
    def schema = s

  /** Build a single-value reader from a name and a parse function. */
  def of[A](name: String)(f: String => Result[A, String]): Reader[A] =
    fromSchema(Schema.Value(name, f))

  /** Opt `A` into repeated shape: each occurrence is parsed with the element's reader,
    * and the collected elements are combined with `build`.
    */
  def repeated[E, A](build: List[E] => Result[A, String])(using element: Reader[E]): Reader[A] =
    fromSchema(Schema.Repeated(element, build))

  given [A](using Reader[A]): Reader[List[A]] = repeated[A, List[A]](l => Ok(l))
  given [A](using Reader[A]): Reader[Vector[A]] = repeated[A, Vector[A]](l => Ok(l.toVector))
  given [A](using Reader[A]): Reader[Seq[A]] = repeated[A, Seq[A]](l => Ok(l))

  private def numeric[A](name: String)(f: String => A): Reader[A] =
    of(name)(s =>
      try Ok(f(s.trim))
      catch case _: NumberFormatException => Err(s"'$s' is not a valid $name")
    )

  given Reader[String]     = of("string")(Ok(_))
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
    if s.length == 1 then Ok(s.charAt(0)) else Err(s"'$s' is not a single character")
  )

  given Reader[Path] = of("path")(s =>
    try Ok(Paths.get(s))
    catch case _: InvalidPathException => Err(s"'$s' is not a valid path")
  )

  given Reader[File] = of("file")(s => Ok(new File(s)))

  given Reader[UUID] = of("uuid")(s =>
    try Ok(UUID.fromString(s.trim))
    catch case _: IllegalArgumentException => Err(s"'$s' is not a valid UUID")
  )

  private def temporal[A](name: String)(f: String => A): Reader[A] =
    of(name)(s =>
      try Ok(f(s.trim))
      catch case _: DateTimeParseException => Err(s"'$s' is not a valid $name")
    )

  given Reader[LocalDate]     = temporal("date")(LocalDate.parse)
  given Reader[LocalTime]     = temporal("time")(LocalTime.parse)
  given Reader[LocalDateTime] = temporal("date-time")(LocalDateTime.parse)
  given Reader[Instant]       = temporal("instant")(Instant.parse)

  given Reader[FiniteDuration] = of("duration")(s =>
    try
      Duration(s.trim) match
        case fd: FiniteDuration => Ok(fd)
        case _                  => Err(s"'$s' is not a finite duration")
    catch case _: NumberFormatException => Err(s"'$s' is not a valid duration (try e.g. '30s' or '5.minutes')")
  )

  /** Derive a reader for an enum (or sealed trait) whose cases are all parameterless:
    * values are matched by kebab-cased case name, case-insensitively.
    * Enables `enum Color derives Reader` and thus `--color red`.
    */
  inline def derived[A](using scala.deriving.Mirror.SumOf[A]): Reader[A] =
    internal.Derive.enumReader[A]
