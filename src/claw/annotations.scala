package claw

import scala.annotation.StaticAnnotation
import claw.internal.Defaults

// Annotations are case classes so they can be rebuilt from an
// `claw.internal.AnnotMirror` via their `Mirror.ProductOf`. Each companion pins a
// `Defaults` instance so annotation materialisation shares one mirror per type
// instead of synthesizing it at every `AnnotMirror.find` site.

/** Override the derived long name of an option, the name of a subcommand, or
  * the default program name when placed on the top-level type.
  * Derived names are the kebab-cased field/case name (`maxDepth` → `--max-depth`).
  */
final case class name(value: String) extends StaticAnnotation
object name:
  given Defaults[name] = Defaults.of[name]

/** Add a single-character short alias for an option, e.g. `@short('v') verbose: Boolean` → `-v`. */
final case class short(value: Char) extends StaticAnnotation
object short:
  given Defaults[short] = Defaults.of[short]

/** Help text shown in `--help` output. Valid on fields, enum cases, and top-level types. */
final case class help(value: String) extends StaticAnnotation
object help:
  given Defaults[help] = Defaults.of[help]

/** Mark a field as a positional argument instead of a named option. */
final case class positional() extends StaticAnnotation
object positional:
  given Defaults[positional] = Defaults.of[positional]
