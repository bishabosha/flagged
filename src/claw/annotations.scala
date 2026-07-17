package claw

import scala.annotation.StaticAnnotation
import claw.meta.Defaults

// Annotations are case classes so they can be rebuilt from an
// `claw.internal.AnnotMirror` via their `Mirror.ProductOf`, and derive `Defaults`
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
