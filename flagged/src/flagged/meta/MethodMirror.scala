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

object MethodMirror:
  /** Alias pattern: lets a match type capture through the `MirroredResult` member, which a
    * refinement written inline in a match-type case cannot do.
    */
  type WithResult[R] = MethodMirror[?] { type MirroredResult = R }

  /** The result type of a refined [[MethodMirror]] type. */
  type ResultOf[M] = M match
    case WithResult[r] => r

/** `Mirror`-style witness for the command members of `T` (an object): its methods and, nested, its
  * member objects marked by a [[Reflectable]] annotation (`@run` in flagged), in declaration order.
  *
  * The mirror is one level deep: nested objects appear only as [[MethodsMirror.Entry.Scope]] tags
  * in [[MirroredEntries]], and callers descend by summoning a `MethodsMirror` for the scope's own
  * type.
  */
sealed trait MethodsMirror[T]:
  /** The object's name. */
  type MirroredLabel <: String

  /** [[Ann]]-encoded annotations on the object itself. */
  type MirroredSelfAnnotations <: Tuple

  /** One [[MethodsMirror.Entry]] tag per member, in declaration order:
    * [[MethodsMirror.Entry.Method]] of the refined [[MethodMirror]] type for a method,
    * [[MethodsMirror.Entry.Scope]] of the object's type for a nested object.
    */
  type MirroredEntries <: Tuple

  /** The mirror for the method at `index` in [[MirroredEntries]].
    *
    * @throws NoSuchElementException
    *   if the entry at `index` is a [[MethodsMirror.Entry.Scope]] (summon a `MethodsMirror` for the
    *   scope's type instead) or out of range.
    */
  def method(index: Int): MethodMirror[T]

object MethodsMirror:
  trait Of[T] extends MethodsMirror[T]

  /** Tagged union describing one element of [[MethodsMirror.MirroredEntries]]. Purely a type-level
    * tag: no values of it are ever constructed.
    */
  enum Entry[E]:
    /** A command method; `M` is the refined [[MethodMirror]] type, retrievable via [[method]]. Its
      * result type is `MethodMirror.ResultOf[M]`.
      */
    case Method[M]() extends Entry[M]

    /** A nested command object; `S` is the object's type — summon a `MethodsMirror[S]` to descend.
      */
    case Scope[S]() extends Entry[S]

  /** Synthesize the mirror for `T`'s [[Reflectable]]-annotated members (macro-backed). Given as
    * `transparent inline` so the refined type members reach the summoning site.
    */
  transparent inline given of[T]: MethodsMirror.Of[T] =
    ${ macros.MethodMacros.methodsMirror[T] }
