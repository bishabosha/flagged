package flagged

/** How a named option attaches an inline value: `--opt=value` ([[Equals]], the default) or
  * `--opt:value` ([[Colon]]). The chosen separator also delimits the attached short form
  * (`-o=value` / `-o:value`); the separate-token form `--opt value` parses in either mode. The
  * separator applies only to the option token itself — values are never rescanned, so a `Map`
  * entry's `key=value` pair keeps `=` in either mode (`--define:a=1`).
  */
enum ValueSeparator:
  case Equals, Colon

  private[flagged] def char: Char = this match
    case Equals => '='
    case Colon  => ':'

/** How long option names are prefixed: `--name` only ([[DoubleDash]], the default), or after either
  * a single or a double dash ([[AnyDash]]) — the javac/scalac convention, where `-Werror`,
  * `-source:3.4`, and their `--` spellings all resolve to the same setting. Under [[AnyDash]] a
  * single-dash token is matched against the long names first; only an unmatched token parses as a
  * short-option cluster, so declared shorts keep working. `-help` (and `-version`, for a
  * [[Versioned]] command) resolve like their double-dash forms.
  */
enum LongPrefix:
  case DoubleDash, AnyDash

/** Parse-time settings, accepted by every `parse` entry point ([[Parser.parse]], [[Flagged.parse]],
  * [[Flagged.parseOrExit]], ...) and applied to the whole command line, subcommands included.
  */
final case class ParserSettings(
    valueSeparator: ValueSeparator = ValueSeparator.Equals,
    longPrefix: LongPrefix = LongPrefix.DoubleDash
)

object ParserSettings:
  /** The default settings — the shared instance behind every `settings` default argument. */
  val default: ParserSettings = ParserSettings()
