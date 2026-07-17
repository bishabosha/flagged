package flagged.meta

/** Default arguments of a case class's constructor, by parameter index. */
trait Defaults[A]:
  /** The default value of constructor parameter `index`; throws `NoSuchElementException` when the
    * parameter has no default (or is out of bounds). Implemented as a switch on the index.
    */
  def defaultArgument(index: Int): Any

  /** Whether constructor parameter `index` declares a default argument. */
  def hasDefault(index: Int): Boolean

object Defaults:
  /** The one thing `Mirror` cannot see: default argument getters. Consumers (e.g.
    * `AnnotMirror.find`) require `Defaults[A]` as a context parameter; add a `derives Defaults`
    * clause to the type (annotations in particular) so the instance is derived once, in its
    * companion.
    */
  inline def derived[A]: Defaults[A] = ${ macros.DefaultMacros.defaults[A] }
