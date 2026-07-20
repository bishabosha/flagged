package flagged.internal

import steps.result.Result

/** Internal runtime model of a derived command. Public because macro-generated code at user call
  * sites must reference these types; not intended for direct use.
  */
enum Mode:
  /** Flag: takes no token; built from the occurrence count (a [[flagged.Parser.ValuedFlag]]
    * additionally handles the explicit `--flag=value` form). If `optional` the field is an
    * `Option[_]`: absent means `None`, any presence wraps the built value in `Some`.
    */
  case Flag(parser: flagged.Parser.Flag[?], optional: Boolean)

  /** Option taking one value. If `optional` the field is an `Option[_]` and the parsed value is
    * wrapped in `Some`.
    */
  case Single(parser: flagged.Parser[?], optional: Boolean)

  /** Option that may appear multiple times; elements are parsed with the parser's element and
    * combined with its build (also invoked with `Nil` when absent; may fail, e.g. to require at
    * least one occurrence).
    */
  case Repeated(parser: flagged.Parser.Repeated[?])

final case class OptSpec(
    long: String,
    short: Option[Char],
    help: String,
    metavar: String,
    index: Int,
    mode: Mode,
    default: Option[() => Any],
    hidden: Boolean = false,
    group: Option[String] = None,
    aliases: List[String] = Nil
):
  lazy val longDisplay: String  = "--" + long
  lazy val shortDisplay: String = short.fold(longDisplay)("-" + _)

final case class PosSpec(
    name: String,
    help: String,
    metavar: String,
    index: Int,
    mode: Mode,
    default: Option[() => Any]
):
  lazy val display: String = "<" + name + ">"

final case class SubCase(
    name: String,
    help: String,
    command: Command,
    hidden: Boolean = false,
    aliases: List[String] = Nil
)

final case class SubGroup(
    index: Int,
    optional: Boolean,
    default: Option[() => Any],
    cases: Vector[SubCase],
    defaultCase: Option[SubCase] = None // @default: run when no command token is given
)

/** An options group spliced into a parent command: the child command's option specs live re-indexed
  * in the parent (at `offset ..< offset + command.arity` of the parent's value storage), and the
  * built child value lands in parent slot `slot`. If `optional` the field is an `Option[_]`: the
  * group is `None` unless at least one of its options occurs on the command line.
  */
final case class Splice(
    slot: Int,
    offset: Int,
    command: Command,
    optional: Boolean = false,
    default: Option[() => Any] = None
)

/** A field collecting the raw arguments after `--`, verbatim. */
final case class TrailingSpec(
    index: Int,
    help: String,
    parser: flagged.Parser.Trailing[?],
    optional: Boolean,
    default: Option[() => Any]
):
  def build(l: List[String]): Result[Any, String] =
    parser.build(l).asInstanceOf[Result[Any, String]]

final case class Command(
    description: String,
    opts: Vector[OptSpec],
    positionals: Vector[PosSpec],
    sub: Option[SubGroup],
    trailing: Option[TrailingSpec],
    splices: List[Splice],
    build: Array[Any] => Result[Any, String], // fallible: `emap` validation composes here
    arity: Int,                    // value-storage size: own fields plus spliced children's storage
    version: Option[String] = None // printed by --version and in the help header
):
  // per-token lookups: java.util maps return null instead of allocating an Option, and the
  // long keys carry their `--` prefix so a plain long token needs no substring at all (and the
  // key doubles as the option's display spelling)
  lazy val longLookup: java.util.HashMap[String, OptSpec] =
    val m = new java.util.HashMap[String, OptSpec]
    opts.foreach { o =>
      m.put("--" + o.long, o)
      o.aliases.foreach(a => m.put("--" + a, o))
    }
    m
  private lazy val shorts: Vector[OptSpec] = opts.filter(_.short.isDefined)
  lazy val shortChars: Array[Char]         = shorts.map(_.short.get).toArray
  lazy val shortSpecs: Array[OptSpec]      = shorts.toArray

  /** Build spliced children from their storage slices, then build this command's value; the first
    * failing build (e.g. an `emap` validation) short-circuits. `counts` holds per-slot mention
    * counts, indexed from `base` for this command's storage: an optional splice none of whose slots
    * were mentioned becomes `None` without being built.
    */
  def finish(values: Array[Any], counts: Array[Int], base: Int): Result[Any, String] =
    def loop(remaining: List[Splice]): Result[Any, String] = remaining match
      case Nil       => build(values)
      case s :: rest =>
        var occupied = false
        var i        = base + s.offset
        val end      = base + s.offset + s.command.arity
        while i < end && !occupied do
          if counts(i) > 0 then occupied = true
          i += 1
        if s.optional && !occupied then
          values(s.slot) = s.default match
            case Some(d) => d()
            case None    => None
          loop(rest)
        else
          s.command.finish(
            values.slice(s.offset, s.offset + s.command.arity),
            counts,
            base + s.offset
          ) match
            case Result.Ok(v) =>
              values(s.slot) = if s.optional then Some(v) else v
              loop(rest)
            case err => err
    loop(splices)

object Command:
  /** A command with no parameters that always produces `value` (parameterless enum case / case
    * object).
    */
  def leaf(value: Any, description: String): Command =
    Command(description, Vector.empty, Vector.empty, None, None, Nil, _ => Result.Ok(value), 0)
