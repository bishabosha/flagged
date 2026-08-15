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

  /** [[ArgumentList]]-encoded annotations on the method itself. */
  type MirroredSelfAnnotations <: Tuple

  /** One [[ArgumentList]]-tuple slot per parameter. */
  type MirroredAnnotations <: Tuple

  /** The method's result type. */
  type MirroredResult

  /** Call the method with one parsed value per parameter. Takes no receiver: `T` is always a static
    * object (enforced when the mirror is synthesized), so the method is selected on it directly.
    */
  def invoke(args: Array[Any]): Any

object MethodMirror:
  /** Alias pattern: lets a match type capture through the `MirroredResult` member, which a
    * refinement written inline in a match-type case cannot do.
    */
  type WithResult[R] = MethodMirror[?] { type MirroredResult = R }

  /** The result type of a refined [[MethodMirror]] type. */
  type ResultOf[M] = M match
    case WithResult[r] => r

  /** Alias pattern for the `MirroredSelfAnnotations` member, like [[WithResult]]. */
  type WithAnnots[A <: Tuple] = MethodMirror[?] { type MirroredSelfAnnotations = A }

  /** The annotations of a refined [[MethodMirror]] type. */
  type AnnotsOf[M] <: Tuple = M match
    case WithAnnots[a] => a

/** `Mirror`-style witness for the command members of `T` (an object): its methods and, nested, its
  * member objects marked by a [[Reflectable]] annotation (`@cmd` in flagged), in declaration order.
  *
  * The mirror is one level deep: nested objects appear only as [[MethodsMirror.Entry.Scope]] tags
  * in [[MirroredEntries]], and callers descend by summoning a `MethodsMirror` for the scope's own
  * type.
  */
sealed trait MethodsMirror[T]:
  /** The object's name. */
  type MirroredLabel <: String

  /** [[ArgumentList]]-encoded annotations on the object itself. */
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

  /** Alias pattern for the `MirroredSelfAnnotations` member, like [[MethodMirror.WithAnnots]]. */
  type WithAnnots[A <: Tuple] = MethodsMirror[?] { type MirroredSelfAnnotations = A }

  /** The annotations of a refined [[MethodsMirror]] type. */
  type AnnotsOf[M] <: Tuple = M match
    case WithAnnots[a] => a

  /** Tagged union describing one element of [[MethodsMirror.MirroredEntries]]. Purely a type-level
    * tag — sealed traits rather than an enum, since no values of it are ever constructed.
    */
  sealed trait Entry[E]
  object Entry:
    /** A command method; `M` is the refined [[MethodMirror]] type, retrievable via [[method]]. Its
      * result type is `MethodMirror.ResultOf[M]`.
      */
    sealed trait Method[M] extends Entry[M]

    /** A nested command object; `S` is the object's type — summon a `MethodsMirror[S]` to descend.
      */
    sealed trait Scope[S] extends Entry[S]

  /** Synthesize the mirror for `T`'s [[Reflectable]]-annotated members (macro-backed). Given as
    * `transparent inline` so the refined type members reach the summoning site.
    */
  transparent inline given of[T]: MethodsMirror.Of[T] =
    ${ macros.MethodMacros.methodsMirror[T] }
