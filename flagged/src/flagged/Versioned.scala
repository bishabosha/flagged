package flagged

/** Provides the version string printed by `--version` and shown in the help header.
  *
  * Required by `@version` on a top-level type. The string is requested when help or `--version` is
  * rendered, not at derivation, so it can be a constant or computed on demand (build info, an
  * environment variable, ...).
  */
@scala.annotation.implicitNotFound(
  "@version on ${A} requires a given Versioned[${A}] that provides the version string."
)
trait Versioned[A]:
  def version: String

object Versioned:
  def apply[A](using v: Versioned[A]): Versioned[A] = v

  /** A constant version string. */
  def of[A](v: String): Versioned[A] = new Versioned[A]:
    def version = v
