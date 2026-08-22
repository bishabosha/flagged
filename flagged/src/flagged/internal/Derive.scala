package flagged.internal

import scala.compiletime.*
import scala.annotation.publicInBinary
import scala.compiletime.ops.int./
import scala.compiletime.ops.int.{BitwiseOr, BitwiseAnd}
import scala.compiletime.ops.any.{==, !=}
import scala.deriving.Mirror
import flagged.Parser
import flagged.meta.{ArgumentList, AnnotMirror, Defaults}

/** `Mirror`-based derivation. Structure and construction come from `Mirror`; field semantics are
  * the field parser's schema: `CommandGroup` instances become nested subcommands, `Shared`
  * instances spliced option groups, value shapes option or positional values (a bare `Command` is
  * not a field shape — a group case with one as its sole field embeds it by substitution). Nothing
  * is derived across type boundaries — each enum or options group in a command tree provides its
  * own instance.
  *
  * The only macro-backed pieces are [[Defaults]] (term-level: default values are arbitrary
  * expressions), [[flagged.meta.AnnotMirror]] (type-level: annotations reduced to singleton types,
  * folded into typed records by [[Annots]]), and [[flagged.meta.MethodsMirror]] (the method-command
  * analogue of `Mirror`, consumed by [[DeriveMethods]]).
  */
@publicInBinary private[flagged] object Derive:

  // ---- parsers ----------------------------------------------------------------

  /** [[ArrayProduct]] behind a term: inline expansions can reference this method but not the
    * private class itself. This unchecked compilation unit is also what carries the borrow
    * assumption capture checking cannot express: the returned `Product` aliases the engine's live
    * storage array, but is consumed by `Mirror#fromProduct` before the engine writes again.
    */
  @publicInBinary private[flagged] def arrayProduct(arr: Array[Any], n: Int): Product =
    ArrayProduct(arr, n)

  /** Offset-aware counterpart used by destination-oriented command builders. */
  @publicInBinary private[flagged] def arrayProductAt(
      arr: Array[Any],
      offset: Int,
      n: Int
  ): Product =
    ArrayProduct(arr, offset, n)

  inline def product[A](using m: Mirror.ProductOf[A]): Parser.Command[A] =
    summonFrom:
      case am: AnnotMirror.Product[A] =>
        val onType = Annots.targetAnnotsOf[am.MirroredSelfAnnotations]
        val cmd    = fieldsOf[m.MirroredElemLabels, m.MirroredElemTypes, am.MirroredAnnotations](
          Defaults.derived[A]
        ).resultInto(
          onType,
          (arr, base, outIndex) =>
            steps.result.Result.task:
              arr(outIndex) = m.fromProduct(
                arrayProductAt(arr, base, constValue[Tuple.Size[m.MirroredElemTypes]])
              )
          ,
          versionOf[A, am.MirroredSelfAnnotations]
        )
        Parser.make[A](cmd, Assemble.progName(constValue[m.MirroredLabel], onType))

  /** A spliceable options group, for `derives Parser.Shared`: the same derivation as [[product]],
    * plus the splice invariants checked on the walk's final marks — no positional, trailing,
    * subcommand, or greedy fields — so a command splicing the group needs no knowledge of its
    * contents.
    */
  inline def shared[A](using m: Mirror.ProductOf[A]): Parser.Shared[A] =
    summonFrom:
      case am: AnnotMirror.Product[A] =>
        val onType = Annots.targetAnnotsOf[am.MirroredSelfAnnotations]
        val cmd    =
          sharedFieldsOf[m.MirroredElemLabels, m.MirroredElemTypes, am.MirroredAnnotations](
            Defaults.derived[A]
          ).resultInto(
            onType,
            (arr, base, outIndex) =>
              steps.result.Result.task:
                arr(outIndex) = m.fromProduct(
                  arrayProductAt(arr, base, constValue[Tuple.Size[m.MirroredElemTypes]])
                )
            ,
            versionOf[A, am.MirroredSelfAnnotations]
          )
        Parser.makeShared[A](cmd, Assemble.progName(constValue[m.MirroredLabel], onType))

  inline def sum[A](using m: Mirror.SumOf[A]): Parser.CommandGroup[A] =
    summonFrom:
      case am: AnnotMirror.Sum[A] =>
        checkSumRules[am.MirroredAnnotations]
        val annots = Annots.sumAnnots[A]
        val cmd    = Assemble.sum(
          labelsOf[m.MirroredElemLabels],
          annots,
          entriesOf[m.MirroredElemTypes],
          versionOf[A, am.MirroredSelfAnnotations]
        )
        Parser.makeGroup[A](cmd, Assemble.progName(constValue[m.MirroredLabel], annots.onType))

  /** `@version` on the type: a non-empty literal `value` argument is the version string as given
    * and takes precedence over `dynamic`; otherwise `dynamic` (the default) requires a
    * [[flagged.Versioned]] instance instead, whose string is requested when printed, not captured
    * at derivation. With `dynamic = false` and no usable literal, the (empty) `value` is used
    * as-is.
    */
  inline def versionOf[A, Anns]: Option[() => String] =
    inline erasedValue[Anns] match
      case _: EmptyTuple                                      => None
      case _: (ArgumentList[flagged.version, ns, vs, ?] *: _) =>
        versionArgs[A, ns, vs, "", true]
      case _: (_ *: t) => versionOf[A, t]

  /** Fold the sparse arguments into the (`value`, `dynamic`) constants, then decide. */
  private inline def versionArgs[A, Ns <: Tuple, Vs <: Tuple, V <: String, Dyn <: Boolean]
      : Option[() => String] =
    inline erasedValue[(Ns, Vs)] match
      case _: (EmptyTuple, ?) =>
        inline erasedValue[V] match
          case _: "" =>
            inline erasedValue[Dyn] match
              // `dynamic = false` with no non-empty literal: the empty value is used as-is
              case _: false => Some(() => "")
              case _        => summonVersioned[A]
          case _ => Some(() => constValue[V])
      case _: ("value" *: nt, v *: vt)   => versionArgs[A, nt, vt, v & String, Dyn]
      case _: ("dynamic" *: nt, v *: vt) => versionArgs[A, nt, vt, V, v & Boolean]
      case _: (? *: nt, ? *: vt)         => versionArgs[A, nt, vt, V, Dyn]

  private inline def summonVersioned[A]: Option[() => String] =
    val v = summonInline[flagged.Versioned[A]]
    Some(() => v.version)

  /** Product parser for a tuple: each element type's `Value` parses one consecutive token. */
  inline def tupleProduct[T <: NonEmptyTuple]: Parser.Product[T] =
    val elems = valuesOfAll[T]
    Parser.productOf[T](
      elems,
      elems.map(_.typeName),
      // unsafeFromArray: the scratch array is per-occurrence and never written after the build
      arr => Tuple.fromIArray(IArray.unsafeFromArray(arr)).asInstanceOf[T]
    )

  /** Product parser for a case class, for `derives Parser.Product`: the fields parse from
    * consecutive tokens, and the kebab-cased field names become the help metavars.
    */
  inline def productValue[A](using m: Mirror.ProductOf[A]): Parser.Product[A] =
    inline erasedValue[m.MirroredElemTypes] match
      case _: EmptyTuple => error("Parser.Product requires at least one field")
      case _             =>
        Parser.productOf[A](
          valuesOfAll[m.MirroredElemTypes],
          IArray.from(labelsOf[m.MirroredElemLabels].map(Assemble.kebab)),
          arr => m.fromProduct(arrayProduct(arr, arr.length))
        )

  /** The `Value` parser of every element type. */
  inline def valuesOfAll[T <: Tuple]: IArray[Parser.Value[?]] =
    summonAll[Tuple.Map[T, Parser.Value]].toIArray.map(_.asInstanceOf[Parser.Value[?]])

  /** Value parser for an enum whose cases are all parameterless, for `Parser.Enumerated`. */
  inline def enumParser[A](using m: Mirror.SumOf[A]): Parser.Enumerated[A] =
    Assemble
      .enumValueParser(
        constValue[m.MirroredLabel],
        labelsOf[m.MirroredElemLabels],
        singletonValues[m.MirroredElemTypes],
        Annots.sumAnnots[A]
      )
      .asInstanceOf[Parser.Enumerated[A]]

  // ---- fields ---------------------------------------------------------------

  inline def labelsOf[L <: Tuple]: IndexedSeq[String] =
    inline erasedValue[L] match
      case _: EmptyTuple => Vector.empty
      case _: (h *: t)   => asIndexedSeq[String](constValueTuple[h *: t])

  /** The single field rule: summon the field type's `Parser`; `Option[_]` marks it optional. The
    * parser's shape (its `Parser` subtype, which derivation requires to be statically known)
    * decides everything else; combinations visible in types are rejected here, at compile time.
    *
    * Fields with no `@opt` annotation are *positional by default* (matching `@scala.main`): a
    * value-shaped field parses as an unnamed argument, and a pure flag (no value parser) is a
    * compile error asking for an `@opt`. Any `@opt` makes the field a named option unless it sets
    * `positional = true` explicitly. The default is per-derivation — `PD` below — because a
    * `Parser.Shared` options group cannot contain positionals: there, unannotated fields stay named
    * options.
    */
  type FieldsB = Assemble.FieldsBuilder

  inline def fieldsOf[Labels <: Tuple, Types <: Tuple, Slots <: Tuple](
      defaults: Defaults[?]
  ): FieldsB =
    // one destructuring match per tuple so the walk's match types (`Take`/`Drop`/`Size`) operate
    // on concrete tuple types: the mirror members arrive as abstract paths (`am.MirroredAnnotations`)
    // that inline-match reduction resolves but match-type reduction alone does not
    inline erasedValue[Types] match
      case _: EmptyTuple => Assemble.fieldsBuilder(0, defaults)
      case _: (t0 *: tr) =>
        inline erasedValue[Slots] match
          case _: (s0 *: sr) =>
            inline erasedValue[Labels] match
              case _: (l0 *: lr) =>
                checkDupNames[s0 *: sr]
                val b = Assemble.fieldsBuilder(constValue[Tuple.Size[t0 *: tr]], defaults)
                walk[l0 *: lr, t0 *: tr, s0 *: sr, true](b)
                b

  /** [[fieldsOf]] plus the `Parser.Shared` splice invariants, read off the walk's final marks.
    * Spliced groups are option bags by definition, so the positional-by-default rule is off here
    * (`PD = false`): an unannotated field is a named option, as before.
    */
  inline def sharedFieldsOf[Labels <: Tuple, Types <: Tuple, Slots <: Tuple](
      defaults: Defaults[?]
  ): FieldsB =
    inline erasedValue[Types] match
      case _: EmptyTuple => Assemble.fieldsBuilder(0, defaults)
      case _: (t0 *: tr) =>
        inline erasedValue[Slots] match
          case _: (s0 *: sr) =>
            inline erasedValue[Labels] match
              case _: (l0 *: lr) =>
                checkDupNames[s0 *: sr]
                val b = Assemble.fieldsBuilder(constValue[Tuple.Size[t0 *: tr]], defaults)
                sharedChecked(walk[l0 *: lr, t0 *: tr, s0 *: sr, false](b))
                b

  /** The invariants read the marks through a type parameter, like [[merge]] — a val binding would
    * widen the transparent walk's refinement.
    */
  type SharedErr[M <: Int] <: String = BitwiseAnd[M, PosBit] match
    case 0 =>
      BitwiseAnd[M, TrailBit] match
        case 0 =>
          BitwiseAnd[M, GroupBit] match
            case 0 =>
              BitwiseAnd[M, GreedyBit] match
                case 0 => ""
                case _ => "a shared options group cannot contain a greedy option"
            case _ => "a shared options group cannot contain a subcommand field"
        case _ => "a shared options group cannot contain a trailing field"
    case _ => "a shared options group cannot contain positional fields"

  private transparent inline def sharedChecked[R <: FieldsRes](r: R): Unit =
    inline if constValue[SharedErr[r.Marks] == ""] then ()
    else error(constValue[SharedErr[r.Marks]])

  /** Per-subtree summary of the field walk — a single marks bitmask, carried in the *type* of a
    * transparent inline result. Summaries are computed bottom-up and combined in [[merge]], where
    * the shape-dependent cross-field rules are checked — no state flows *into* a subtree, so the
    * two halves of a split are independent, type arguments stay small, and inline depth is
    * logarithmic in the field count. (Duplicate constant names are shape-independent and checked
    * once per product in [[checkDupNames]] instead.)
    *
    * A pure type carrier: the runtime payload accumulates in the builder threaded through [[walk]]
    * (one collection factory call per product), so every summary is the same shared instance cast
    * to its refinement.
    */
  final class FieldsRes private[Derive] ():
    type Marks <: Int // bitmask of TrailBit | RepBit | PosBit | GroupBit | GreedyBit

  type NoMarks   = 0
  type TrailBit  = 1
  type RepBit    = 2
  type PosBit    = 4
  type GroupBit  = 8
  type GreedyBit = 16

  // shape codes for the field dispatch
  type ValueShape      = 1
  type FlagShape       = 2
  type ValuedFlagShape = 3
  type RepeatedShape   = 4
  type TrailingShape   = 5
  type SharedShape     = 6 // a spliced options group
  type GroupShape      = 7 // a subcommand group
  type ProductShape    = 8 // a fixed-arity multi-token value

  type ResOf[M <: Int] = FieldsRes { type Marks = M }

  private val fieldsRes = FieldsRes()

  def resOf[M <: Int]: ResOf[M] = fieldsRes.asInstanceOf[ResOf[M]]

  /** A plain named option's summary: nothing special. */
  def plainRes: ResOf[NoMarks] = resOf[NoMarks]

  inline def isZero[M <: Int]: Boolean = constValue[M == 0]

  type HalfN[T <: Tuple] = Tuple.Size[T] / 2

  /** Whether the field's annotation slot is empty — the common case; lets every per-field
    * annotation check and name collection collapse to nothing. `PD` is the derivation's
    * positional-by-default rule, threaded to each field.
    */
  private transparent inline def walk[
      Labels <: Tuple,
      Types <: Tuple,
      Slots <: Tuple,
      PD <: Boolean
  ](
      inline b: FieldsB
  ): FieldsRes =
    inline erasedValue[Types] match
      case _: EmptyTuple         => plainRes
      case _: (f1 *: EmptyTuple) =>
        inline erasedValue[Slots] match
          case _: (a1 *: ?) =>
            inline erasedValue[Labels] match
              case _: (l1 *: ?) => fieldRes[l1, f1, a1, PD](b)
      case _: (f1 *: f2 *: EmptyTuple) =>
        inline erasedValue[Slots] match
          case _: (a1 *: a2 *: ?) =>
            inline erasedValue[Labels] match
              case _: (l1 *: l2 *: ?) =>
                merge(fieldRes[l1, f1, a1, PD](b), fieldRes[l2, f2, a2, PD](b))
      case _: (f1 *: f2 *: f3 *: EmptyTuple) =>
        inline erasedValue[Slots] match
          case _: (a1 *: a2 *: a3 *: ?) =>
            inline erasedValue[Labels] match
              case _: (l1 *: l2 *: l3 *: ?) =>
                merge(
                  merge(fieldRes[l1, f1, a1, PD](b), fieldRes[l2, f2, a2, PD](b)),
                  fieldRes[l3, f3, a3, PD](b)
                )
      case _: (f1 *: f2 *: f3 *: f4 *: EmptyTuple) =>
        inline erasedValue[Slots] match
          case _: (a1 *: a2 *: a3 *: a4 *: ?) =>
            inline erasedValue[Labels] match
              case _: (l1 *: l2 *: l3 *: l4 *: ?) =>
                merge4(
                  fieldRes[l1, f1, a1, PD](b),
                  fieldRes[l2, f2, a2, PD](b),
                  fieldRes[l3, f3, a3, PD](b),
                  fieldRes[l4, f4, a4, PD](b)
                )
      case _: NonEmptyTuple =>
        merge(
          walk[
            Tuple.Take[Labels, HalfN[Types]],
            Tuple.Take[Types, HalfN[Types]],
            Tuple.Take[Slots, HalfN[Types]],
            PD
          ](b),
          walk[
            Tuple.Drop[Labels, HalfN[Types]],
            Tuple.Drop[Types, HalfN[Types]],
            Tuple.Drop[Slots, HalfN[Types]],
            PD
          ](b)
        )

  /** Combine two subtree summaries: all cross-field rules are checked where the subtrees meet (each
    * rule involves two fields, one in each half at exactly one merge).
    */
  private transparent inline def merge[L <: FieldsRes, R <: FieldsRes](l: L, r: R): FieldsRes =
    inline if constValue[CrossErr[l.Marks, r.Marks] == ""] then ()
    else error(constValue[CrossErr[l.Marks, r.Marks]])
    resOf[BitwiseOr[l.Marks, r.Marks]]

  /** Four-way merge for unrolled leaf groups: the all-plain fast path is a single gate; anything
    * special delegates to nested pairwise merges (identical semantics: the summary is associative
    * and every check is pairwise).
    */
  private transparent inline def merge4[
      A <: FieldsRes,
      B <: FieldsRes,
      C <: FieldsRes,
      D <: FieldsRes
  ](a: A, b: B, c: C, d: D): FieldsRes =
    inline if isZero[
        BitwiseOr[BitwiseOr[a.Marks, b.Marks], BitwiseOr[c.Marks, d.Marks]]
      ]
    then plainRes
    else merge(merge(a, b), merge(c, d))

  // The cross-field rules where two subtree summaries meet, in the same check-and-report style
  // as [[FieldErr]] and [[DupNameErr]]: message-returning match types over the two marks words.
  // The marks are literals by the time these reduce, so the `BitwiseAnd` scrutinees fold on
  // constants (the cheap regime measured in `bench.RuleCostProbe`); each rule involves two
  // fields, one in each half, at exactly one merge.

  type CrossErr[LM <: Int, RM <: Int] <: String = BitwiseAnd[LM, TrailBit] match
    case 0 => CrossGroupL[LM, RM]
    case _ =>
      BitwiseAnd[RM, TrailBit] match
        case 0 => CrossGroupL[LM, RM]
        case _ => "only one trailing field is supported per command"

  type CrossGroupL[LM <: Int, RM <: Int] <: String = BitwiseAnd[LM, GroupBit] match
    case 0 => CrossGroupR[LM, RM]
    case _ =>
      BitwiseAnd[RM, GroupBit] match
        case 0 =>
          BitwiseAnd[RM, PosBit] match
            case 0 => CrossGroupR[LM, RM]
            case _ =>
              "mixing positional fields with a subcommand field is ambiguous and not supported"
        case _ => "only one subcommand field is supported per command"

  type CrossGroupR[LM <: Int, RM <: Int] <: String = BitwiseAnd[RM, GroupBit] match
    case 0 => CrossRep[LM, RM]
    case _ =>
      BitwiseAnd[LM, PosBit] match
        case 0 => CrossRep[LM, RM]
        case _ => "mixing positional fields with a subcommand field is ambiguous and not supported"

  type CrossRep[LM <: Int, RM <: Int] <: String = BitwiseAnd[LM, RepBit] match
    case 0 => CrossGreedyL[LM, RM]
    case _ =>
      BitwiseAnd[RM, PosBit] match
        case 0 => CrossGreedyL[LM, RM]
        case _ => "a repeated positional must be the last positional field"

  type CrossGreedyL[LM <: Int, RM <: Int] <: String = BitwiseAnd[LM, GreedyBit] match
    case 0 => CrossGreedyR[LM, RM]
    case _ =>
      BitwiseAnd[RM, PosBit] match
        case 0 =>
          BitwiseAnd[RM, GroupBit] match
            case 0 => CrossGreedyR[LM, RM]
            case _ => "a command with a greedy option cannot have a subcommand field"
        case _ => "a command with a greedy option cannot have positional fields"

  type CrossGreedyR[LM <: Int, RM <: Int] <: String = BitwiseAnd[RM, GreedyBit] match
    case 0 => ""
    case _ =>
      BitwiseAnd[LM, PosBit] match
        case 0 =>
          BitwiseAnd[LM, GroupBit] match
            case 0 => ""
            case _ => "a command with a greedy option cannot have a subcommand field"
        case _ => "a command with a greedy option cannot have positional fields"

  /** `Option[_]` unwrapping at the type level, so one transparent expansion handles a field. */
  type Unwrap[F] = F match
    case Option[e] => e
    case _         => F

  type IsOpt[F] <: Boolean = F match
    case Option[?] => true
    case _         => false

  /** The field rules: the `Option[_]` shape guards, then one pass over the annotation slot
    * ([[SlotRules]]). Every scrutinee is *data* — the slot tuple, a destructured element, or a
    * literal parameter — never an unreduced computation: a match type reduces its scrutinee
    * strictly and, when nested inside an outer reduction, without memoization, so computation
    * belongs in the arms (measured in [[bench.RuleCostProbe]]; a presence-query scrutinee costs
    * ~7-13 ms per file, an ops-based one far more).
    */
  type FieldErr[S <: Int, Anns, Opt <: Boolean, PD <: Boolean] <: String = Opt match
    case true =>
      S match
        case FlagShape     => "a flag Parser without a value parser cannot be used inside Option"
        case RepeatedShape =>
          "Option of a repeated Parser is not supported: the plain type is empty when absent"
        case _ => SlotRules[S, Anns, PD]
    case false => SlotRules[S, Anns, PD]

  /** One pass over the slot: annotations aimed at other targets are rejected, and the `@opt`
    * occurrence dispatches to [[OptRules]] over its sparse arguments. A slot with no `@opt` at all
    * falls to [[NoOptErr]]: the field is implicitly positional (when `PD` says so), which a pure
    * flag shape cannot be.
    */
  type SlotRules[S <: Int, Anns, PD <: Boolean] <: String = Anns match
    case EmptyTuple                                  => NoOptErr[S, PD]
    case ArgumentList[flagged.version, ?, ?, ?] *: ? =>
      "@version has no effect on a field (put it on the top-level type)"
    case ArgumentList[flagged.cmd, ?, ?, ?] *: ? =>
      "@cmd has no effect on a field (commands are types, enum cases, methods, and objects)"
    case ArgumentList[flagged.opt, ns, vs, ?] *: t =>
      OptRules[
        S,
        ns,
        vs,
        t,
        false,
        false,
        false,
        false,
        false,
        false,
        false
      ]
    case ? *: t => SlotRules[S, t, PD]

  /** The rule for a field with no `@opt`: positional by default (like `@scala.main`), which is fine
    * for every value shape — including `Boolean`, whose valued-flag parser reads a `true`/`false`
    * token — but impossible for a pure flag with no value parser.
    */
  type NoOptErr[S <: Int, PD <: Boolean] <: String = PD match
    case false => ""
    case true  =>
      S match
        case FlagShape =>
          "an unannotated field is positional by default, and a flag Parser without a value parser cannot be positional: add @opt to make it a named flag"
        case _ => ""

  /** One pass over an `@opt`'s sparse argument columns; `P`/`Sh`/`H`/`G`/`Sp`/`Gr`/`NH` accumulate
    * `positional = true`, `short`, `hidden = true`, `group`, `split`, `greedy = true`, and a
    * constant name or alias `"help"`. Argument-target mismatches report as each argument is passed;
    * combinations report where the walk ends ([[OptCombErr]]) — with a single occurrence, every
    * argument is in scope by then, in whatever order they were written.
    */
  type OptRules[
      S <: Int,
      Ns <: Tuple,
      Vs <: Tuple,
      Rest,
      P <: Boolean,
      Sh <: Boolean,
      H <: Boolean,
      G <: Boolean,
      Sp <: Boolean,
      Gr <: Boolean,
      NH <: Boolean
  ] <: String = (Ns, Vs) match
    case (EmptyTuple, ?)         => OptCombErr[S, Rest, P, Sh, H, G, Sp, Gr, NH]
    case ("name" *: nt, v *: vt) =>
      S match
        case TrailingShape => "@opt(name) has no effect on a trailing field"
        case GroupShape    =>
          "@opt(name) has no effect on a subcommand field (command names come from the cases)"
        case _ =>
          v match
            case "help" => OptRules[S, nt, vt, Rest, P, Sh, H, G, Sp, Gr, true]
            case _      => OptRules[S, nt, vt, Rest, P, Sh, H, G, Sp, Gr, NH]
    case ("aliases" *: nt, v *: vt) =>
      S match
        case TrailingShape => "@opt(aliases) has no effect on a trailing field"
        case GroupShape    =>
          "@opt(aliases) has no effect on a subcommand field (command names come from the cases)"
        case _ =>
          Tuple.Contains[v & Tuple, "help"] match
            case true  => OptRules[S, nt, vt, Rest, P, Sh, H, G, Sp, Gr, true]
            case false => OptRules[S, nt, vt, Rest, P, Sh, H, G, Sp, Gr, NH]
    case ("short" *: nt, v *: vt) =>
      v match
        case 'h' => "short option 'h' is reserved for help"
        case _   =>
          S match
            case TrailingShape => "@opt(short) cannot be combined with a trailing field"
            case GroupShape    => "@opt(short) has no effect on a subcommand field"
            case SharedShape   => "@opt(short) has no effect on a spliced options group"
            case _             => OptRules[S, nt, vt, Rest, P, true, H, G, Sp, Gr, NH]
    case ("help" *: nt, v *: vt) =>
      S match
        case GroupShape =>
          "@opt(help) has no effect on a subcommand field (put it on the enum or its cases)"
        case SharedShape => "@opt(help) has no effect on a spliced options group"
        case _           => OptRules[S, nt, vt, Rest, P, Sh, H, G, Sp, Gr, NH]
    case ("positional" *: nt, v *: vt) =>
      v match
        case false => OptRules[S, nt, vt, Rest, P, Sh, H, G, Sp, Gr, NH]
        case true  =>
          S match
            case TrailingShape => "@opt(positional) cannot be combined with a trailing field"
            case GroupShape    => "@opt(positional) cannot be combined with a subcommand field"
            case SharedShape   => "@opt(positional) cannot be combined with a command-shaped Parser"
            case FlagShape     => "a flag Parser without a value parser cannot be used positionally"
            case _             => OptRules[S, nt, vt, Rest, true, Sh, H, G, Sp, Gr, NH]
    case ("hidden" *: nt, v *: vt) =>
      v match
        case false => OptRules[S, nt, vt, Rest, P, Sh, H, G, Sp, Gr, NH]
        case true  =>
          S match
            case TrailingShape => "@opt(hidden) has no effect on a trailing field"
            case GroupShape    =>
              "@opt(hidden) has no effect on a subcommand field (put it on the enum cases)"
            case SharedShape =>
              "@opt(hidden) has no effect on a spliced options group (put it on the group's fields)"
            case _ => OptRules[S, nt, vt, Rest, P, Sh, true, G, Sp, Gr, NH]
    case ("group" *: nt, v *: vt) =>
      S match
        case TrailingShape => "@opt(group) has no effect on a trailing field"
        case GroupShape    => "@opt(group) has no effect on a subcommand field"
        case _             => OptRules[S, nt, vt, Rest, P, Sh, H, true, Sp, Gr, NH]
    case ("split" *: nt, v *: vt) =>
      S match
        case RepeatedShape => OptRules[S, nt, vt, Rest, P, Sh, H, G, true, Gr, NH]
        case _             => "@opt(split) requires a field with a repeated Parser (a collection)"
    case ("greedy" *: nt, v *: vt) =>
      v match
        case false => OptRules[S, nt, vt, Rest, P, Sh, H, G, Sp, Gr, NH]
        case true  =>
          S match
            case RepeatedShape => OptRules[S, nt, vt, Rest, P, Sh, H, G, Sp, true, NH]
            case _ => "@opt(greedy) requires a field with a repeated Parser (a collection)"
    case (? *: nt, ? *: vt) => OptRules[S, nt, vt, Rest, P, Sh, H, G, Sp, Gr, NH]

  /** The argument combinations, checked where the walk ends, then the rest of the slot. */
  type OptCombErr[
      S <: Int,
      Rest,
      P <: Boolean,
      Sh <: Boolean,
      H <: Boolean,
      G <: Boolean,
      Sp <: Boolean,
      Gr <: Boolean,
      NH <: Boolean
  ] <: String = P match
    case true =>
      Sh match
        case true  => "@opt(short) cannot be combined with @opt(positional)"
        case false =>
          H match
            case true  => "@opt(hidden) cannot be combined with @opt(positional)"
            case false =>
              G match
                case true  => "@opt(group) cannot be combined with @opt(positional)"
                case false =>
                  Gr match
                    case true =>
                      "@opt(greedy) has no effect on a positional field (a repeated positional is already greedy)"
                    case false => OptSplitErr[S, Rest, Sp, Gr]
    case false =>
      NH match
        case true  => "option name 'help' is reserved"
        case false => OptSplitErr[S, Rest, Sp, Gr]

  type OptSplitErr[S <: Int, Rest, Sp <: Boolean, Gr <: Boolean] <: String = Sp match
    case true =>
      Gr match
        case true  => "@opt(split) cannot be combined with @opt(greedy)"
        case false => RestRules[S, Rest]
    case false => RestRules[S, Rest]

  /** The slot after its `@opt`: a second `@opt` is ambiguous, and the other annotations stay
    * rejected wherever they appear.
    */
  type RestRules[S <: Int, Anns] <: String = Anns match
    case EmptyTuple                                  => ""
    case ArgumentList[flagged.version, ?, ?, ?] *: ? =>
      "@version has no effect on a field (put it on the top-level type)"
    case ArgumentList[flagged.cmd, ?, ?, ?] *: ? =>
      "@cmd has no effect on a field (commands are types, enum cases, methods, and objects)"
    case ArgumentList[flagged.opt, ?, ?, ?] *: ? => "duplicate @opt annotation"
    case ? *: t                                  => RestRules[S, t]

  /** The [[FieldsRes.Marks]] contribution of a field of shape `S` with annotations `Anns`. */
  type MarksOf[S <: Int, Anns, PD <: Boolean] <: Int = S match
    case TrailingShape => TrailBit
    case GroupShape    => GroupBit
    case SharedShape   => NoMarks
    case _             =>
      PosGreedyMark[Anns, S, PD]

  /** One walk finds `positional = true` or `greedy = true` (never both — a [[FieldErr]] rules the
    * combination out before marks matter). A slot with no `@opt` is implicitly positional when `PD`
    * says so ([[ImplicitPosMark]]).
    */
  type PosGreedyMark[Anns, S <: Int, PD <: Boolean] <: Int = Anns match
    case EmptyTuple                                => ImplicitPosMark[S, PD]
    case ArgumentList[flagged.opt, ns, vs, ?] *: _ =>
      PosGreedyArg[ns, vs, S]
    case _ *: t => PosGreedyMark[t, S, PD]

  /** The marks of an implicitly positional field: value shapes claim [[PosBit]] (a repeated one is
    * a repeated positional); a pure flag shape claims nothing — it is a [[NoOptErr]] error before
    * marks matter.
    */
  type ImplicitPosMark[S <: Int, PD <: Boolean] <: Int = PD match
    case false => NoMarks
    case true  =>
      S match
        case RepeatedShape => BitwiseOr[RepBit, PosBit]
        case FlagShape     => NoMarks
        case _             => PosBit

  type PosGreedyArg[Ns <: Tuple, Vs <: Tuple, S <: Int] <: Int = (Ns, Vs) match
    case (EmptyTuple, ?)                => NoMarks
    case ("positional" *: ?, true *: ?) =>
      S match
        case RepeatedShape => BitwiseOr[RepBit, PosBit]
        case _             => PosBit
    case ("greedy" *: ?, true *: ?) => GreedyBit
    case (? *: nt, ? *: vt)         => PosGreedyArg[nt, vt, S]

  /** Whether a field of shape `S` with no `@opt` parses positionally under default rule `PD` —
    * decides which empty record the extraction hands to the builder.
    */
  type ImplicitlyPositional[S <: Int, PD <: Boolean] <: Boolean = PD match
    case false => false
    case true  =>
      S match
        case ValueShape      => true
        case ValuedFlagShape => true
        case RepeatedShape   => true
        case ProductShape    => true
        case _               => false

  /** A field either fails with its match-type-computed error or constructs exactly one summary. */
  private transparent inline def fin[L, S <: Int, F, Anns, PD <: Boolean](
      inline p: Parser[?],
      inline b: FieldsB
  ): FieldsRes =
    inline if constValue[FieldErr[S, Anns, IsOpt[F], PD] == ""] then ()
    else error(constValue[FieldErr[S, Anns, IsOpt[F], PD]])
    def annots() = Annots.fieldAnnotsOf[Anns](constValue[ImplicitlyPositional[S, PD]])
    b.addField(constValue[L & String], p, constValue[IsOpt[F]], annots())
    resOf[MarksOf[S, Anns, PD]]

  /** One field: instance selection (the field's single implicit search) and shape dispatch — the
    * only parts that are inherently term-level. The dispatch must be an inline match on the
    * instance *tree*: a summonFrom binder's static type is the pattern type, so the precise subtype
    * is invisible to match types.
    */
  private transparent inline def fieldRes[L, F, Anns, PD <: Boolean](inline b: FieldsB): FieldsRes =
    summonFrom:
      case p: Parser[Unwrap[F]] =>
        inline p match
          case _: Parser.Value[?]        => fin[L, ValueShape, F, Anns, PD](p, b)
          case _: Parser.ValuedFlag[?]   => fin[L, ValuedFlagShape, F, Anns, PD](p, b)
          case _: Parser.Flag[?]         => fin[L, FlagShape, F, Anns, PD](p, b)
          case _: Parser.Product[?]      => fin[L, ProductShape, F, Anns, PD](p, b)
          case _: Parser.Repeated[?]     => fin[L, RepeatedShape, F, Anns, PD](p, b)
          case _: Parser.Trailing[?]     => fin[L, TrailingShape, F, Anns, PD](p, b)
          case _: Parser.CommandGroup[?] => fin[L, GroupShape, F, Anns, PD](p, b)
          case _: Parser.Shared[?]       => fin[L, SharedShape, F, Anns, PD](p, b)
          case _: Parser.Command[?]      =>
            error(
              "a command-shaped Parser cannot be a field: derive Parser.Shared for a spliceable options group, or Parser.CommandGroup for subcommands (a full command can be embedded as the sole field of a command-group case)"
            )
          case _ =>
            error(
              "the shape of this field's Parser is not statically known: give the given a shape type such as Parser.Value[X], or build it with the Parser constructors / derivation clauses"
            )
      case _ =>
        // fails with Parser's missing-instance guidance
        b.addField(
          constValue[L & String],
          summonInline[Parser[Unwrap[F]]],
          constValue[IsOpt[F]],
          FieldAnnots.empty
        )
        plainRes

  /** The constant `short` character / long names (`name` and `aliases`) a slot's `@opt` claims, as
    * tuples.
    */
  type ShortsOf[Anns] <: Tuple = Anns match
    case ArgumentList[flagged.opt, ns, vs, ?] *: ? =>
      ArgShorts[ns, vs]
    case ? *: t     => ShortsOf[t]
    case EmptyTuple => EmptyTuple

  type ArgShorts[Ns <: Tuple, Vs <: Tuple] <: Tuple = (Ns, Vs) match
    case (EmptyTuple, ?)        => EmptyTuple
    case ("short" *: ?, v *: ?) => v *: EmptyTuple
    case (? *: nt, ? *: vt)     => ArgShorts[nt, vt]

  type LongsOf[Anns] <: Tuple = Anns match
    case ArgumentList[flagged.opt, ns, vs, ?] *: ? =>
      ArgLongs[ns, vs]
    case ? *: t     => LongsOf[t]
    case EmptyTuple => EmptyTuple

  /** The constant `name` and `aliases` names of one sparse argument list — shared between `@opt`
    * (option names) and `@cmd` (command names), whose arguments are spelled the same.
    */
  type ArgLongs[Ns <: Tuple, Vs <: Tuple] <: Tuple = (Ns, Vs) match
    case (EmptyTuple, ?)            => EmptyTuple
    case ("name" *: nt, v *: vt)    => v *: ArgLongs[nt, vt]
    case ("aliases" *: nt, v *: vt) => Tuple.Concat[v & Tuple, ArgLongs[nt, vt]]
    case (? *: nt, ? *: vt)         => ArgLongs[nt, vt]

  /** Whether the two constant-name tuples share an element. */
  type OverlapsT[A <: Tuple, B <: Tuple] <: Boolean = B match
    case EmptyTuple => false
    case h *: t     =>
      Tuple.Contains[A, h] match
        case true  => true
        case false => OverlapsT[A, t]

  /** Duplicate constant names across the whole product, one fold over the annotation slots. A
    * field's constant names participate unless it is positional (positional fields claim no option
    * names); no shape knowledge is needed — a name argument on a shape that could not claim it is
    * already a [[FieldErr]] error.
    */
  type SlotShorts[S] <: Tuple = IsPositionalSlot[S] match
    case true  => EmptyTuple
    case false => ShortsOf[S]

  type SlotLongs[S] <: Tuple = IsPositionalSlot[S] match
    case true  => EmptyTuple
    case false => LongsOf[S]

  /** Whether the slot's `@opt` sets `positional = true` — a compile-time constant. */
  type IsPositionalSlot[Anns] <: Boolean = Anns match
    case EmptyTuple                                => false
    case ArgumentList[flagged.opt, ns, vs, ?] *: ? =>
      ArgsPositional[ns, vs]
    case ? *: t => IsPositionalSlot[t]

  type ArgsPositional[Ns <: Tuple, Vs <: Tuple] <: Boolean = (Ns, Vs) match
    case (EmptyTuple, ?)                => false
    case ("positional" *: ?, true *: ?) => true
    case (? *: nt, ? *: vt)             => ArgsPositional[nt, vt]

  type DupNameErr[Slots <: Tuple] = DupNameErrAcc[Slots, EmptyTuple, EmptyTuple]

  type DupNameErrAcc[Slots <: Tuple, Sh <: Tuple, Lo <: Tuple] <: String = Slots match
    case EmptyTuple => ""
    case s *: t     =>
      OverlapsT[Sh, SlotShorts[s]] match
        case true  => "duplicate short option"
        case false =>
          OverlapsT[Lo, SlotLongs[s]] match
            case true  => "duplicate option name"
            case false =>
              DupNameErrAcc[t, Tuple.Concat[Sh, SlotShorts[s]], Tuple.Concat[Lo, SlotLongs[s]]]

  inline def checkDupNames[Slots <: Tuple]: Unit =
    inline if constValue[DupNameErr[Slots] == ""] then ()
    else error(constValue[DupNameErr[Slots]])

  // ---- sum-level rules --------------------------------------------------------

  /** Sum-level compile checks over the per-case annotation slots: at most one
    * `@cmd(default = true)` case, and no duplicate constant command names (`@cmd` names and
    * aliases) across cases. Kebab-derived command-name collisions are value-level and checked at
    * construction in [[Assemble.sum]].
    */
  inline def checkSumRules[Slots]: Unit =
    inline erasedValue[Slots] match
      case _: EmptyTuple => ()
      case _: (s0 *: sr) =>
        inline if constValue[MultiDefaultT[s0 *: sr, false]] then
          error("only one @cmd(default = true) command is supported")
        else ()
        inline if constValue[DupCaseNameErr[s0 *: sr] == ""] then ()
        else error(constValue[DupCaseNameErr[s0 *: sr]])

  /** Whether more than one case carries `@cmd(default = true)`. */
  type MultiDefaultT[Slots, Seen <: Boolean] <: Boolean = Slots match
    case EmptyTuple => false
    case s *: t     =>
      IsDefaultCmd[s] match
        case true =>
          Seen match
            case true  => true
            case false => MultiDefaultT[t, true]
        case false => MultiDefaultT[t, Seen]

  /** Whether the slot's `@cmd` sets `default = true` — a compile-time constant. */
  type IsDefaultCmd[Anns] <: Boolean = Anns match
    case EmptyTuple                                => false
    case ArgumentList[flagged.cmd, ns, vs, ?] *: ? =>
      ArgsDefault[ns, vs]
    case ? *: t => IsDefaultCmd[t]

  type ArgsDefault[Ns <: Tuple, Vs <: Tuple] <: Boolean = (Ns, Vs) match
    case (EmptyTuple, ?)             => false
    case ("default" *: ?, true *: ?) => true
    case (? *: nt, ? *: vt)          => ArgsDefault[nt, vt]

  /** The constant command names (`name` plus `aliases`) a `@cmd` slot claims. */
  type CmdNamesOf[Anns] <: Tuple = Anns match
    case ArgumentList[flagged.cmd, ns, vs, ?] *: ? =>
      ArgLongs[ns, vs]
    case ? *: t     => CmdNamesOf[t]
    case EmptyTuple => EmptyTuple

  /** Duplicate constant command names across the sum's cases, one fold over the slots. */
  type DupCaseNameErr[Slots] = DupCaseNameErrAcc[Slots, EmptyTuple]

  type DupCaseNameErrAcc[Slots, Seen <: Tuple] <: String = Slots match
    case EmptyTuple => ""
    case s *: t     =>
      OverlapsT[Seen, CmdNamesOf[s]] match
        case true  => "duplicate command name"
        case false => DupCaseNameErrAcc[t, Tuple.Concat[Seen, CmdNamesOf[s]]]

  /** Whether annotation slot `Anns` contains an `A` — a compile-time constant. Match types rather
    * than inline-match recursion: reduction happens in the (cached) type domain instead of one
    * inline expansion per slot element.
    */
  type HasAnnT[A <: scala.annotation.Annotation, Anns] <: Boolean = Anns match
    case EmptyTuple                    => false
    case ArgumentList[A, ?, ?, ?] *: _ => true
    case _ *: t                        => HasAnnT[A, t]

  // ---- sums -----------------------------------------------------------------

  // Note: `case _: (h *: t)` binders widen term-singleton types (`L.A.type` becomes
  // `L`), which breaks `ValueOf` summoning for enum cases. `Tuple.Head`/`Tuple.Tail`
  // are match types and preserve the exact element type, so sums are traversed with
  // those instead.

  inline def entriesOf[T <: Tuple]: IndexedSeq[SubEntry] =
    inline erasedValue[T] match
      case _: EmptyTuple    => Vector.empty
      case _: NonEmptyTuple =>
        val b = Vector.newBuilder[SubEntry]
        entriesInto[T](b)
        b.result()

  inline def entriesInto[T <: Tuple](b: scala.collection.mutable.Growable[SubEntry]): Unit =
    inline erasedValue[T] match
      case _: EmptyTuple        => ()
      case _: (? *: EmptyTuple) => b += entryOf[Tuple.Head[T & NonEmptyTuple]]
      case _: NonEmptyTuple     =>
        entriesInto[Tuple.Take[T, HalfN[T]]](b)
        entriesInto[Tuple.Drop[T, HalfN[T]]](b)

  /** One case of the sum being derived. Singleton and product cases belong to the sum's own
    * declaration and are handled in place; a case that is itself a sum is a separate hierarchy and
    * must provide its own `Parser` instance.
    */
  inline def entryOf[H]: SubEntry =
    summonFrom:
      case v: ValueOf[H]          => SubEntry.Leaf(v.value)
      case p: Parser[H]           => SubEntry.Node(p)
      case m: Mirror.ProductOf[H] => SubEntry.Node(caseCommand[H](using m))
      case _                      => SubEntry.Node(summonInline[Parser[H]])

  /** A product case of the sum: normally derived in place; a case whose sole field carries a full
    * `Parser.Command` embeds that command wholesale — substitution: the case's grammar *is* the
    * embedded command's (options, positionals, trailing and all — safe, because subcommand dispatch
    * delegates the remaining tokens rather than merging grammars), and the build wraps its result
    * in the case constructor. `@cmd(name/help/hidden)` still applies on the *case*; `Shared` and
    * `CommandGroup` fields keep their splice/nesting meaning. Deliberately scoped to group cases: a
    * top-level wrapper class has no need for it (use the command's parser directly).
    */
  inline def caseCommand[H](using m: Mirror.ProductOf[H]): Parser[H] =
    inline erasedValue[m.MirroredElemTypes] match
      case _: (e *: EmptyTuple) =>
        // decided by a boolean probe so `product[H]` expands outside any summonFrom binder —
        // a `p: Parser[e]` binder in scope would shadow the field givens its inner summons need
        inline if isEmbeddableCommand[e] then embedCase[H, e]
        else product[H]
      case _ => product[H]

  /** Whether `E`'s instance is a full command — not a subcommand group, not a shared group. */
  private transparent inline def isEmbeddableCommand[E]: Boolean =
    // searching Parser.Command[E] (not Parser[E]) lets implicit search discard every value-shaped
    // candidate on the result-type head alone, with no per-candidate trial
    summonFrom:
      case p: Parser.Command[E] =>
        inline p match
          case _: Parser.CommandGroup[?] => false
          case _: Parser.Shared[?]       => false
          case _                         => true
      case _ => false

  /** The substitution itself: the embedded command's grammar with the build composed with the case
    * constructor. The sole field may not carry annotations (they could not take effect — rename or
    * hide via the annotations on the enum case).
    */
  inline def embedCase[H, E](using m: Mirror.ProductOf[H]): Parser[H] =
    summonFrom:
      case am: AnnotMirror.Product[H] =>
        inline erasedValue[am.MirroredAnnotations] match
          case _: (EmptyTuple *: EmptyTuple) => ()
          case _                             =>
            error(
              "annotations have no effect on an embedded command field (put @cmd(name/help/hidden) on the enum case)"
            )
        val c = summonInline[Parser.Command[E]]
        Parser.make[H](
          c.emapImpl(a => steps.result.Result.Ok(m.fromProduct(Tuple1(a)))),
          c.prog
        )

  // ---- singleton helpers ------------------------------------------------------

  // not a constValueTuple walk like the label and alias columns: an enum case is a *term*
  // singleton (`E.A.type`), not a literal constant type, so `constValue` rejects it — the witness
  // for a term singleton is `ValueOf`, summoned per case below, which also carries the tailored
  // error for a parameterized case
  inline def singletonValues[T <: Tuple]: IndexedSeq[Any] =
    inline erasedValue[T] match
      case _: EmptyTuple    => Vector.empty
      case _: NonEmptyTuple =>
        val b = Vector.newBuilder[Any]
        singletonsInto[T](b)
        b.result()

  inline def singletonsInto[T <: Tuple](b: scala.collection.mutable.Growable[Any]): Unit =
    inline erasedValue[T] match
      case _: EmptyTuple        => ()
      case _: (? *: EmptyTuple) =>
        summonFrom:
          case v: ValueOf[Tuple.Head[T & NonEmptyTuple]] => b += v.value
          case _                                         =>
            error(
              "Parser.Enumerated requires an enum (or sealed trait) whose cases are all parameterless"
            )
      case _: NonEmptyTuple =>
        singletonsInto[Tuple.Take[T, HalfN[T]]](b)
        singletonsInto[Tuple.Drop[T, HalfN[T]]](b)
