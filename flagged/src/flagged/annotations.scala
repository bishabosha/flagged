package flagged

import scala.annotation.StaticAnnotation
import flagged.meta.Defaults

// Annotations are case classes so they can be rebuilt from an
// `flagged.internal.AnnotMirror` via their `Mirror.ProductOf`, and derive `Defaults`
// so materialisation shares one defaults mirror per annotation type.
//
// All customisation lives in two annotations with named arguments — `@cmd` for commands and
// command groups (types, enum cases, methods, nested objects), `@opt` for fields and method
// parameters — sparsely mirrored: only the arguments written at the use site are encoded
// ([[flagged.meta.ArgumentList]]), so an argument left at its default costs nothing at derivation.

/** Mark and customise a command or command group.
  *
  * Valid on the top-level type of a command or group, on a command-group enum case, and — for
  * `Parser.method` / `Parser.methods` / `Flagged.Entry` — on a method (its parameters become the
  * options and positionals, and parsing invokes it) or a nested object (a group of subcommands).
  * Methods and nested objects are commands *only* when marked `@cmd`; commands are invoked without
  * a receiver, so the enclosing object must be reachable without one — top-level, nested in other
  * objects, or local to a method. An object declared inside a class or trait has a per-instance
  * receiver and is rejected.
  *
  * @param name
  *   overrides the derived command name (the kebab-cased type, case, or method name) — or the
  *   default program name when placed on the top-level type; `""` derives it
  * @param help
  *   help text shown in `--help` output
  * @param hidden
  *   omit the subcommand from help output; it still parses, only the listing is suppressed
  * @param default
  *   mark one command of a group as the default: selected when no command token is given, with the
  *   remaining arguments forwarded to it
  * @param aliases
  *   extra constant command names, as a tuple of string literals (`aliases = ("co", "get")` or
  *   `"co" *: EmptyTuple`)
  */
final case class cmd(
    name: String = "",
    help: String = "",
    hidden: Boolean = false,
    default: Boolean = false,
    aliases: Tuple = EmptyTuple
) extends meta.Reflectable derives Defaults

/** Customise one field (or method parameter) of a command: an option, positional, or spliced group.
  *
  * A field with no `@opt` at all is *positional by default*, matching `@scala.main` — a `Boolean`
  * reads a `true`/`false` token, and a pure flag parser with no value parser (`Count`,
  * `Parser.flag`) is a compile error asking for an `@opt`. Writing `@opt` (with any arguments, or
  * none) makes the field a named option unless it sets `positional = true` explicitly. The one
  * exception is a `Parser.Shared` options group, which cannot contain positionals: there,
  * unannotated fields stay named options.
  *
  * @param name
  *   overrides the derived long name (the kebab-cased field name: `maxDepth` → `--max-depth`); `""`
  *   derives it
  * @param help
  *   help text shown in `--help` output
  * @param short
  *   a single-character short alias, e.g. `@opt(short = 'v') verbose: Boolean` → `-v`; `'\u0000'`
  *   (the default) means none
  * @param aliases
  *   extra constant long names, as a tuple of string literals
  * @param positional
  *   parse the field as a positional argument instead of a named option — required on an `@opt`
  *   that also carries metadata (`help`, ...) for a positional field, since any `@opt` otherwise
  *   means "named option"
  * @param hidden
  *   omit the option from help output; it still parses, only the listing is suppressed
  * @param group
  *   put the option under a titled section in help output (`Output options:`). On a spliced options
  *   group field, titles all of the group's options that have no group of their own; `""` means
  *   none
  * @param split
  *   split each occurrence's value at the separator and parse every segment with the element
  *   parser: `@opt(split = ',') env: List[String]` parses `--env A,B,C` as three elements. Valid on
  *   fields with a repeated `Parser` (collections, `Map[K, V]`, `Parser.repeated` types);
  *   occurrences still accumulate, so `--env A,B --env C` also yields three. Segments are taken
  *   verbatim between separators (no escaping; empty segments are offered to the element parser as
  *   empty strings). `'\u0000'` (the default) means no splitting
  * @param greedy
  *   let each occurrence of a repeated option consume the following tokens up to the next
  *   option-like token or `--`: `@opt(greedy = true) nums: List[Int]` parses `--nums 10 20 99` as
  *   three elements (the `--nums=v` and attached short forms still supply exactly one). To keep the
  *   grammar unambiguous, a command containing a greedy option may not declare positional or
  *   subcommand fields (a `Trailing` field is fine — `--` delimits it)
  */
final case class opt(
    name: String = "",
    help: String = "",
    short: Char = '\u0000',
    aliases: Tuple = EmptyTuple,
    positional: Boolean = false,
    hidden: Boolean = false,
    group: String = "",
    split: Char = '\u0000',
    greedy: Boolean = false
) extends StaticAnnotation derives Defaults

/** Opt into a `--version` flag and a version line in the help header. Valid on the top-level type
  * of a command or command group. `@version("0.1.0")` prints the given literal; without one,
  * `dynamic` (true by default, so bare `@version` implies it) requires a given [[Versioned]]
  * instance for the type, which supplies the version string when it is printed. A non-empty `value`
  * takes precedence over `dynamic`. The command may not also declare an option or alias named
  * `version`.
  */
final case class version(value: String = "", dynamic: Boolean = true) extends StaticAnnotation
    derives Defaults
