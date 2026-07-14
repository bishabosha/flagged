package claw.internal

/** Internal runtime model of a derived command. Public because macro-generated code
  * at user call sites must reference these types; not intended for direct use.
  */
enum Mode:
  /** Boolean option: present → true; also accepts `--flag=false`. */
  case Flag

  /** Option taking one value. If `optional` the field is an `Option[_]` and the
    * parsed value is wrapped in `Some`.
    */
  case Single(read: String => Either[String, Any], optional: Boolean)

  /** Option that may appear multiple times; collected values are converted with `fromList`. */
  case Repeated(read: String => Either[String, Any], fromList: List[Any] => Any)

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

final case class Command(
    description: String,
    opts: Vector[OptSpec],
    positionals: Vector[PosSpec],
    sub: Option[SubGroup],
    build: Array[Any] => Any,
    arity: Int
)

object Command:
  /** A command with no parameters that always produces `value` (parameterless enum case / case object). */
  def leaf(value: Any, description: String): Command =
    Command(description, Vector.empty, Vector.empty, None, _ => value, 0)
