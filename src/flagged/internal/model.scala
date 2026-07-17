package flagged.internal

import steps.result.Result

/** Internal runtime model of a derived command. Public because macro-generated code at user call
  * sites must reference these types; not intended for direct use.
  */
enum Mode:
  /** Flag: takes no token; built from the occurrence count. `fromValue`, when available, handles
    * the explicit `--flag=value` form.
    */
  case Flag(fromCount: Int => Result[Any, String], fromValue: Option[String => Result[Any, String]])

  /** Option taking one value. If `optional` the field is an `Option[_]` and the parsed value is
    * wrapped in `Some`.
    */
  case Single(read: String => Result[Any, String], optional: Boolean)

  /** Option that may appear multiple times; collected values are combined with `fromList` (also
    * invoked with `Nil` when absent; may fail, e.g. to require at least one occurrence).
    */
  case Repeated(read: String => Result[Any, String], fromList: List[Any] => Result[Any, String])

final case class OptSpec(
    long: String,
    short: Option[Char],
    help: String,
    metavar: String,
    index: Int,
    mode: Mode,
    default: Option[() => Any]
)

final case class PosSpec(
    name: String,
    help: String,
    metavar: String,
    index: Int,
    mode: Mode,
    default: Option[() => Any]
)

final case class SubCase(name: String, help: String, command: Command)

final case class SubGroup(
    index: Int,
    optional: Boolean,
    default: Option[() => Any],
    cases: Vector[SubCase]
)

/** An options group spliced into a parent command: the child command's option specs live re-indexed
  * in the parent (at `offset ..< offset + command.arity` of the parent's value storage), and the
  * built child value lands in parent slot `slot`.
  */
final case class Splice(slot: Int, offset: Int, command: Command)

/** A field collecting the raw arguments after `--`, verbatim. */
final case class TrailingSpec(
    index: Int,
    help: String,
    build: List[String] => Result[Any, String],
    optional: Boolean,
    default: Option[() => Any]
)

final case class Command(
    description: String,
    opts: Vector[OptSpec],
    positionals: Vector[PosSpec],
    sub: Option[SubGroup],
    trailing: Option[TrailingSpec],
    splices: List[Splice],
    build: Array[Any] => Result[Any, String], // fallible: `emap` validation composes here
    arity: Int // value-storage size: own fields plus spliced children's storage
):
  /** Build spliced children from their storage slices, then build this command's value; the first
    * failing build (e.g. an `emap` validation) short-circuits.
    */
  def finish(values: Array[Any]): Result[Any, String] =
    def loop(remaining: List[Splice]): Result[Any, String] = remaining match
      case Nil       => build(values)
      case s :: rest =>
        s.command.finish(values.slice(s.offset, s.offset + s.command.arity)) match
          case Result.Ok(v) =>
            values(s.slot) = v
            loop(rest)
          case err => err
    loop(splices)

object Command:
  /** A command with no parameters that always produces `value` (parameterless enum case / case
    * object).
    */
  def leaf(value: Any, description: String): Command =
    Command(description, Vector.empty, Vector.empty, None, None, Nil, _ => Result.Ok(value), 0)
