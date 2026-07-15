package claw

import scala.annotation.StaticAnnotation

// Annotations are case classes so they can be rebuilt from an
// `claw.internal.AnnotMirror` via their `Mirror.ProductOf`.

/** Override the derived long name of an option, the name of a subcommand, or
  * the default program name when placed on the top-level type.
  * Derived names are the kebab-cased field/case name (`maxDepth` → `--max-depth`).
  */
final case class name(value: String) extends StaticAnnotation

/** Add a single-character short alias for an option, e.g. `@short('v') verbose: Boolean` → `-v`. */
final case class short(value: Char) extends StaticAnnotation

/** Help text shown in `--help` output. Valid on fields, enum cases, and top-level types. */
final case class help(value: String) extends StaticAnnotation

/** Mark a field as a positional argument instead of a named option. */
final case class positional() extends StaticAnnotation

/** Force a field whose type is an enum / sealed trait to be treated as a group of
  * subcommands even when all of its cases are parameterless (which would otherwise
  * be parsed as a plain value, e.g. `--color red`).
  */
final case class subcommands() extends StaticAnnotation
