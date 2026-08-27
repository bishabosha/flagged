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

/** Parse-time settings, accepted by every `parse` entry point ([[Parser.parse]], [[Flagged.parse]],
  * [[Flagged.parseOrExit]], ...) and applied to the whole command line, subcommands included.
  */
final case class ParserSettings(valueSeparator: ValueSeparator = ValueSeparator.Equals)

object ParserSettings:
  /** The default settings — the shared instance behind every `settings` default argument. */
  val default: ParserSettings = ParserSettings()
