package flagged

import scala.annotation.StaticAnnotation
import flagged.meta.Defaults

// Annotations are case classes so they can be rebuilt from an
// `flagged.internal.AnnotMirror` via their `Mirror.ProductOf`, and derive `Defaults`
// so materialisation shares one defaults mirror per annotation type.

/** Override the derived long name of an option, the name of a subcommand, or the default program
  * name when placed on the top-level type. Derived names are the kebab-cased field/case name
  * (`maxDepth` → `--max-depth`).
  */
final case class name(value: String) extends StaticAnnotation derives Defaults

/** Add a single-character short alias for an option, e.g. `@short('v') verbose: Boolean` → `-v`. */
final case class short(value: Char) extends StaticAnnotation derives Defaults

/** Help text shown in `--help` output. Valid on fields, enum cases, and top-level types. */
final case class help(value: String) extends StaticAnnotation derives Defaults

/** Mark a field as a positional argument instead of a named option. */
final case class positional() extends StaticAnnotation derives Defaults

/** Omit an option (on a field) or a subcommand (on an enum case) from help output. It still parses;
  * only the listing is suppressed.
  */
final case class hidden() extends StaticAnnotation derives Defaults

/** Opt into a `--version` flag and a version line in the help header. Valid on the top-level type
  * of a command or command group; requires a given [[Versioned]] instance for the type, which
  * supplies the version string when it is printed.
  */
final case class version() extends StaticAnnotation derives Defaults

/** Put an option under a titled section in help output (`Output options:`). On a spliced options
  * group field, titles all of the group's options that have no group of their own.
  */
final case class group(value: String) extends StaticAnnotation derives Defaults

/** Mark one case of a command-group enum as the default command: selected when no command token is
  * given, with the remaining arguments forwarded to it.
  */
final case class default() extends StaticAnnotation derives Defaults

/** Split each occurrence's value at `sep` and parse every segment with the element parser:
  * `@split env: List[String]` parses `--env A,B,C` as three elements. Valid on fields with a
  * repeated `Parser` (collections, `Map[K, V]`, `Parser.repeated` types); occurrences still
  * accumulate, so `--env A,B --env C` also yields three. Segments are taken verbatim between
  * separators (no escaping; empty segments are offered to the element parser as empty strings).
  */
final case class split(sep: Char = ',') extends StaticAnnotation derives Defaults

/** Let each occurrence of a repeated option consume the following tokens up to the next option-like
  * token or `--`: `@greedy nums: List[Int]` parses `--nums 10 20 99` as three elements (the
  * `--nums=v` and attached short forms still supply exactly one). To keep the grammar unambiguous,
  * a command containing a `@greedy` option may not declare positional or subcommand fields (a
  * `Trailing` field is fine — `--` delimits it).
  */
final case class greedy() extends StaticAnnotation derives Defaults

/** Mark a method as a command for `Parser.method` / `Parser.methods`: its parameters become the
  * options and positionals, and parsing invokes it. On a nested object, marks it as a group of
  * subcommands.
  */
final case class run() extends meta.Reflectable derives Defaults
