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

/** Program version, shown in the help header and printed by `--version`. Valid on the top-level
  * type of a command or command group.
  */
final case class version(value: String) extends StaticAnnotation derives Defaults

/** Put an option under a titled section in help output (`Output options:`). On a spliced options
  * group field, titles all of the group's options that have no group of their own.
  */
final case class group(value: String) extends StaticAnnotation derives Defaults

/** Mark one case of a command-group enum as the default command: selected when no command token is
  * given, with the remaining arguments forwarded to it.
  */
final case class default() extends StaticAnnotation derives Defaults
