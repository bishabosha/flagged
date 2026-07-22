package flagged.meta

/** `Mirror`-style witness for one command method of `T`: parameter structure and annotations as
  * type members — the same encodings `Mirror` and [[AnnotMirror]] use, so flagged's inline
  * derivation consumes them unchanged — with the two things types cannot carry as the term-level
  * residue: [[invoke]] (calling the method with the parsed values) and the method's default
  * arguments (via the inherited [[Defaults]]).
  */
trait MethodMirror[T] extends Defaults[Any]:
  /** The method name. */
  type MirroredLabel <: String

  /** Parameter names, as constant types. */
  type MirroredElemLabels <: Tuple

  /** Parameter types. */
  type MirroredElemTypes <: Tuple

  /** [[Ann]]-encoded annotations on the method itself. */
  type MirroredSelfAnnotations <: Tuple

  /** One [[Ann]]-tuple slot per parameter. */
  type MirroredAnnotations <: Tuple

  /** The method's result type. */
  type MirroredResult

  /** Call the method with one parsed value per parameter. */
  def invoke(receiver: T, args: Array[Any]): Any

/** `Mirror`-style witness for the command members of `T` (an object): its methods and, nested, its
  * member objects marked by a [[Reflectable]] annotation (`@run` in flagged), in declaration order.
  */
sealed trait MethodsMirror[T]:
  /** The object's name. */
  type MirroredLabel <: String

  /** [[Ann]]-encoded annotations on the object itself. */
  type MirroredSelfAnnotations <: Tuple

  /** The refined types of [[entries]]' elements: [[MethodMirror]] for a method, [[MethodsMirror]]
    * for a nested object.
    */
  type MirroredEntries <: Tuple

  /** The union of every (transitively) reachable method's result type. */
  type MirroredResult

  def entries: MirroredEntries

object MethodsMirror:
  trait Of[T] extends MethodsMirror[T]

  /** Synthesize the mirror for `T`'s [[Reflectable]]-annotated members (macro-backed). Given as
    * `transparent inline` so the refined type members reach the summoning site.
    */
  transparent inline given of[T]: MethodsMirror.Of[T] =
    ${ macros.MethodMacros.methodsMirror[T] }
