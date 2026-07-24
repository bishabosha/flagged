package flagged.internal

import scala.compiletime.*
import scala.compiletime.ops.int./
import scala.compiletime.ops.int.{BitwiseOr, BitwiseAnd}
import scala.compiletime.ops.any.{==, !=}
import scala.deriving.Mirror
import flagged.Parser
import flagged.meta.{Ann, AnnotMirror, Defaults}

/** `Mirror`-based derivation. Structure and construction come from `Mirror`; field semantics are
  * the field parser's schema: `CommandGroup` instances become nested subcommands, `Shared`
  * instances spliced option groups, value shapes option or positional values (a bare `Command` is
  * not a field shape — a group case with one as its sole field embeds it by substitution). Nothing
  * is derived across type boundaries — each enum or options group in a command tree provides its
  * own instance.
  *
  * The only macro-backed pieces are [[Defaults]] (term-level: default values are arbitrary
  * expressions), [[flagged.meta.AnnotMirror]] (type-level: annotations reduced to singleton types,
  * extracted here via [[flagged.meta.AnnotMirror.find]] into typed [[Annots]]), and
  * [[flagged.meta.MethodsMirror]] (the method-command analogue of `Mirror`, consumed by
  * [[DeriveMethods]]).
  */
object Derive:

  // ---- parsers ----------------------------------------------------------------

  inline def product[A](using m: Mirror.ProductOf[A]): Parser.Command[A] =
    summonFrom:
      case am: AnnotMirror.Product[A] =>
        val onType = Annots.targetAnnotsOf[am.MirroredSelfAnnotations]
        val cmd    = Assemble.product(
          labelsOf[m.MirroredElemLabels],
          fieldsOf[m.MirroredElemTypes, am.MirroredAnnotations],
          Defaults.derived[A],
          onType,
          arr => steps.result.Result.Ok(m.fromProduct(ArrayProduct(arr))),
          versionOf[A, am.MirroredSelfAnnotations]
        )
        Parser.make[A](cmd, Assemble.progName(constValue[m.MirroredLabel], onType))

  /** A spliceable options group, for `derives Parser.Shared`: the same derivation as [[product]],
    * plus the splice invariants checked on the walk's final marks — no positional, trailing,
    * subcommand, or `@greedy` fields — so a command splicing the group needs no knowledge of its
    * contents.
    */
  inline def shared[A](using m: Mirror.ProductOf[A]): Parser.Shared[A] =
    summonFrom:
      case am: AnnotMirror.Product[A] =>
        val onType = Annots.targetAnnotsOf[am.MirroredSelfAnnotations]
        val cmd    = Assemble.product(
          labelsOf[m.MirroredElemLabels],
          sharedFieldsOf[m.MirroredElemTypes, am.MirroredAnnotations],
          Defaults.derived[A],
          onType,
          arr => steps.result.Result.Ok(m.fromProduct(ArrayProduct(arr))),
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

  /** `@version` on the type requires a [[flagged.Versioned]] instance; the string is requested when
    * printed, not captured at derivation.
    */
  inline def versionOf[A, Anns]: Option[() => String] =
    inline erasedValue[Anns] match
      case _: EmptyTuple                        => None
      case _: (Ann[flagged.version, ?, ?] *: _) =>
        val v = summonInline[flagged.Versioned[A]]
        Some(() => v.version)
      case _: (_ *: t) => versionOf[A, t]

  /** Product parser for a tuple: each element type's `Value` parses one consecutive token. */
  inline def tupleProduct[T <: NonEmptyTuple]: Parser.Product[T] =
    val elems = valuesOfAll[T]
    Parser.productOf[T](
      elems,
      elems.map(_.typeName),
      arr => Tuple.fromArray(arr).asInstanceOf[T]
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
          arr => m.fromProduct(ArrayProduct(arr))
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
        Annots.sumAnnots[A].perCase
      )
      .asInstanceOf[Parser.Enumerated[A]]

  // ---- fields ---------------------------------------------------------------

  inline def labelsOf[L <: Tuple]: List[String] =
    inline erasedValue[L] match
      case _: EmptyTuple             => Nil
      case _: (a *: EmptyTuple)      => constValue[a].asInstanceOf[String] :: Nil
      case _: (a *: b *: EmptyTuple) =>
        constValue[a].asInstanceOf[String] :: constValue[b].asInstanceOf[String] :: Nil
      case _: (a *: b *: c *: EmptyTuple) =>
        constValue[a].asInstanceOf[String] :: constValue[b].asInstanceOf[String] ::
          constValue[c].asInstanceOf[String] :: Nil
      case _: (a *: b *: c *: d *: EmptyTuple) =>
        constValue[a].asInstanceOf[String] :: constValue[b].asInstanceOf[String] ::
          constValue[c].asInstanceOf[String] :: constValue[d].asInstanceOf[String] :: Nil
      case _: NonEmptyTuple =>
        labelsOf[Tuple.Take[L, HalfN[L]]] ::: labelsOf[Tuple.Drop[L, HalfN[L]]]

  /** The single field rule: summon the field type's `Parser`; `Option[_]` marks it optional. The
    * parser's shape (its `Parser` subtype, which derivation requires to be statically known)
    * decides everything else; combinations visible in types are rejected here, at compile time.
    */
  inline def fieldsOf[Types <: Tuple, Slots <: Tuple]: List[(Parser[?], Boolean, FieldAnnots)] =
    // one destructuring match per tuple so the walk's match types (`Take`/`Drop`/`Size`) operate
    // on concrete tuple types: the mirror members arrive as abstract paths (`am.MirroredAnnotations`)
    // that inline-match reduction resolves but match-type reduction alone does not
    inline erasedValue[Types] match
      case _: EmptyTuple => Nil
      case _: (t0 *: tr) =>
        inline erasedValue[Slots] match
          case _: (s0 *: sr) =>
            checkDupNames[s0 *: sr]
            walk[t0 *: tr, s0 *: sr].fields

  /** [[fieldsOf]] plus the `Parser.Shared` splice invariants, read off the walk's final marks. */
  inline def sharedFieldsOf[Types <: Tuple, Slots <: Tuple]
      : List[(Parser[?], Boolean, FieldAnnots)] =
    inline erasedValue[Types] match
      case _: EmptyTuple => Nil
      case _: (t0 *: tr) =>
        inline erasedValue[Slots] match
          case _: (s0 *: sr) =>
            checkDupNames[s0 *: sr]
            sharedChecked(walk[t0 *: tr, s0 *: sr])

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
                case _ => "a shared options group cannot contain a @greedy option"
            case _ => "a shared options group cannot contain a subcommand field"
        case _ => "a shared options group cannot contain a trailing field"
    case _ => "a shared options group cannot contain positional fields"

  private transparent inline def sharedChecked[R <: FieldsRes](
      r: R
  ): List[(Parser[?], Boolean, FieldAnnots)] =
    inline if constValue[SharedErr[r.Marks] == ""] then ()
    else error(constValue[SharedErr[r.Marks]])
    r.fields

  /** Per-subtree summary of the field walk — a single marks bitmask, carried in the *type* of a
    * transparent inline result. Summaries are computed bottom-up and combined in [[merge]], where
    * the shape-dependent cross-field rules are checked — no state flows *into* a subtree, so the
    * two halves of a split are independent, type arguments stay small, and inline depth is
    * logarithmic in the field count. (Duplicate constant names are shape-independent and checked
    * once per product in [[checkDupNames]] instead.)
    */
  final class FieldsRes(val fields: List[(Parser[?], Boolean, FieldAnnots)]):
    type Marks <: Int    // bitmask of TrailBit | RepBit | PosBit | GroupBit | NamesBit
    type Shorts <: Tuple // constant `@short` characters claimed by named options
    type Longs <: Tuple  // constant `@name` names (primary and aliases) claimed by named options

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

  inline def resOf[M <: Int](
      inline fields: List[(Parser[?], Boolean, FieldAnnots)]
  ): ResOf[M] =
    new FieldsRes(fields).asInstanceOf[ResOf[M]]

  /** A plain named option's summary: nothing special. */
  inline def plainRes(inline fields: List[(Parser[?], Boolean, FieldAnnots)]) =
    resOf[NoMarks](fields)

  inline def isZero[M <: Int]: Boolean = constValue[M == 0]

  type HalfN[T <: Tuple] = Tuple.Size[T] / 2

  /** Whether the field's annotation slot is empty — the common case; lets every per-field
    * annotation check and name collection collapse to nothing.
    */
  private transparent inline def walk[Types <: Tuple, Slots <: Tuple]: FieldsRes =
    inline erasedValue[Types] match
      case _: EmptyTuple         => plainRes(Nil)
      case _: (f1 *: EmptyTuple) =>
        inline erasedValue[Slots] match
          case _: (a1 *: ?) => fieldRes[f1, a1]
      case _: (f1 *: f2 *: EmptyTuple) =>
        inline erasedValue[Slots] match
          case _: (a1 *: a2 *: ?) => merge(fieldRes[f1, a1], fieldRes[f2, a2])
      case _: (f1 *: f2 *: f3 *: EmptyTuple) =>
        inline erasedValue[Slots] match
          case _: (a1 *: a2 *: a3 *: ?) =>
            merge(merge(fieldRes[f1, a1], fieldRes[f2, a2]), fieldRes[f3, a3])
      case _: (f1 *: f2 *: f3 *: f4 *: EmptyTuple) =>
        inline erasedValue[Slots] match
          case _: (a1 *: a2 *: a3 *: a4 *: ?) =>
            merge4(fieldRes[f1, a1], fieldRes[f2, a2], fieldRes[f3, a3], fieldRes[f4, a4])
      case _: NonEmptyTuple =>
        merge(
          walk[Tuple.Take[Types, HalfN[Types]], Tuple.Take[Slots, HalfN[Types]]],
          walk[Tuple.Drop[Types, HalfN[Types]], Tuple.Drop[Slots, HalfN[Types]]]
        )

  /** Combine two subtree summaries: all cross-field rules are checked where the subtrees meet (each
    * rule involves two fields, one in each half at exactly one merge).
    */
  private transparent inline def merge[L <: FieldsRes, R <: FieldsRes](l: L, r: R): FieldsRes =
    inline if constValue[CrossErr[l.Marks, r.Marks] == ""] then ()
    else error(constValue[CrossErr[l.Marks, r.Marks]])
    resOf[BitwiseOr[l.Marks, r.Marks]](l.fields ++ r.fields)

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
    then plainRes(a.fields ++ b.fields ++ c.fields ++ d.fields)
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
            case _ => "a command with a @greedy option cannot have a subcommand field"
        case _ => "a command with a @greedy option cannot have positional fields"

  type CrossGreedyR[LM <: Int, RM <: Int] <: String = BitwiseAnd[RM, GreedyBit] match
    case 0 => ""
    case _ =>
      BitwiseAnd[LM, PosBit] match
        case 0 =>
          BitwiseAnd[LM, GroupBit] match
            case 0 => ""
            case _ => "a command with a @greedy option cannot have a subcommand field"
        case _ => "a command with a @greedy option cannot have positional fields"

  /** `Option[_]` unwrapping at the type level, so one transparent expansion handles a field. */
  type Unwrap[F] = F match
    case Option[e] => e
    case _         => F

  type IsOpt[F] <: Boolean = F match
    case Option[?] => true
    case _         => false

  /** Shape codes for the dispatch below: 1 Value, 2 Flag, 3 ValuedFlag, 4 Repeated, 5 Trailing, 6
    * Command (splice), 7 CommandGroup. Everything a field contributes — its error verdict, its
    * [[FieldsRes.Marks]] bits, and the constant names it claims — is a match type over
    * `(code, annotations, optionality)`, so fields with the same combination share one cached
    * reduction.
    */
  /** The field rules as one pass over the annotation slot. Every scrutinee is *data* — the slot
    * tuple, a destructured element, or a literal parameter — never an unreduced computation: a
    * match type reduces its scrutinee strictly and, when nested inside an outer reduction, without
    * memoization, so computation belongs in the arms (measured in [[bench.RuleCostProbe]]; a
    * presence-query scrutinee costs ~7-13 ms per file, an ops-based one far more). Pairwise rules
    * thread seen-flags as literal parameters and fire on the second sighting in either order;
    * `@name("help")` defers to the end because its rule only applies to non-positional fields, and
    * `@positional` may appear later.
    */
  type FieldErr[S <: Int, Anns, Opt <: Boolean] <: String = Opt match
    case true =>
      S match
        case FlagShape     => "a flag Parser without a value parser cannot be used inside Option"
        case RepeatedShape =>
          "Option of a repeated Parser is not supported: the plain type is empty when absent"
        case _ => Rules[S, Anns, false, false, false, false, false, false, false]
    case false => Rules[S, Anns, false, false, false, false, false, false, false]

  /** One rule pass; `P`/`Sh`/`H`/`G`/`Sp`/`Gr`/`NH` are the seen-flags for `@positional`, `@short`,
    * `@hidden`, `@group`, `@split`, `@greedy`, and `@name("help")`.
    */
  type Rules[
      S <: Int,
      Anns,
      P <: Boolean,
      Sh <: Boolean,
      H <: Boolean,
      G <: Boolean,
      Sp <: Boolean,
      Gr <: Boolean,
      NH <: Boolean
  ] <: String = Anns match
    case EmptyTuple =>
      NH match
        case true =>
          P match
            case true  => ""
            case false => "option name 'help' is reserved"
        case false => ""
    case Ann[flagged.short, args, ?] *: t =>
      args match
        case 'h' *: EmptyTuple => "short option 'h' is reserved for help"
        case _                 =>
          P match
            case true  => "@short cannot be combined with @positional"
            case false =>
              S match
                case TrailingShape => "@short cannot be combined with a trailing field"
                case GroupShape    => "@short has no effect on a subcommand field"
                case SharedShape   => "@short has no effect on a spliced options group"
                case _             => Rules[S, t, P, true, H, G, Sp, Gr, NH]
    case Ann[flagged.name, args, ?] *: t =>
      S match
        case TrailingShape => "@name has no effect on a trailing field"
        case GroupShape    =>
          "@name has no effect on a subcommand field (command names come from the cases)"
        case _ =>
          args match
            case "help" *: EmptyTuple => Rules[S, t, P, Sh, H, G, Sp, Gr, true]
            case _                    => Rules[S, t, P, Sh, H, G, Sp, Gr, NH]
    case Ann[flagged.help, ?, ?] *: t =>
      S match
        case GroupShape =>
          "@help has no effect on a subcommand field (put it on the enum or its cases)"
        case SharedShape => "@help has no effect on a spliced options group"
        case _           => Rules[S, t, P, Sh, H, G, Sp, Gr, NH]
    case Ann[flagged.positional, ?, ?] *: t =>
      S match
        case TrailingShape => "@positional cannot be combined with a trailing field"
        case GroupShape    => "@positional cannot be combined with a subcommand field"
        case SharedShape   => "@positional cannot be combined with a command-shaped Parser"
        case FlagShape     => "a flag Parser without a value parser cannot be used positionally"
        case _             =>
          H match
            case true  => "@hidden cannot be combined with @positional"
            case false =>
              G match
                case true  => "@group cannot be combined with @positional"
                case false =>
                  Sh match
                    case true  => "@short cannot be combined with @positional"
                    case false =>
                      Gr match
                        case true =>
                          "@greedy has no effect on a positional field (a repeated positional is already greedy)"
                        case false => Rules[S, t, true, Sh, H, G, Sp, Gr, NH]
    case Ann[flagged.hidden, ?, ?] *: t =>
      S match
        case TrailingShape => "@hidden has no effect on a trailing field"
        case GroupShape    =>
          "@hidden has no effect on a subcommand field (put it on the enum cases)"
        case SharedShape =>
          "@hidden has no effect on a spliced options group (put it on the group's fields)"
        case _ =>
          P match
            case true  => "@hidden cannot be combined with @positional"
            case false => Rules[S, t, P, Sh, true, G, Sp, Gr, NH]
    case Ann[flagged.group, ?, ?] *: t =>
      S match
        case TrailingShape => "@group has no effect on a trailing field"
        case GroupShape    => "@group has no effect on a subcommand field"
        case _             =>
          P match
            case true  => "@group cannot be combined with @positional"
            case false => Rules[S, t, P, Sh, H, true, Sp, Gr, NH]
    case Ann[flagged.version, ?, ?] *: ? =>
      "@version has no effect on a field (put it on the top-level type)"
    case Ann[flagged.default, ?, ?] *: ? =>
      "@default has no effect on a field (put it on a command-group enum case)"
    case Ann[flagged.split, ?, ?] *: t =>
      Gr match
        case true  => "@split cannot be combined with @greedy"
        case false =>
          S match
            case RepeatedShape => Rules[S, t, P, Sh, H, G, true, Gr, NH]
            case _             => "@split requires a field with a repeated Parser (a collection)"
    case Ann[flagged.greedy, ?, ?] *: t =>
      Sp match
        case true  => "@split cannot be combined with @greedy"
        case false =>
          S match
            case RepeatedShape =>
              P match
                case true =>
                  "@greedy has no effect on a positional field (a repeated positional is already greedy)"
                case false => Rules[S, t, P, Sh, H, G, Sp, true, NH]
            case _ => "@greedy requires a field with a repeated Parser (a collection)"
    case ? *: t => Rules[S, t, P, Sh, H, G, Sp, Gr, NH]

  /** The [[FieldsRes.Marks]] contribution of a field of shape `S` with annotations `Anns`. */
  type MarksOf[S <: Int, Anns] <: Int = S match
    case TrailingShape => TrailBit
    case GroupShape    => GroupBit
    case SharedShape   => NoMarks
    case _             =>
      PosGreedyMark[Anns, S]

  /** One walk finds `@positional` or `@greedy` (never both — a [[FieldErr]] rules the combination
    * out before marks matter).
    */
  type PosGreedyMark[Anns, S <: Int] <: Int = Anns match
    case EmptyTuple                         => NoMarks
    case Ann[flagged.positional, ?, ?] *: _ =>
      S match
        case RepeatedShape => BitwiseOr[RepBit, PosBit]
        case _             => PosBit
    case Ann[flagged.greedy, ?, ?] *: _ => GreedyBit
    case _ *: t                         => PosGreedyMark[t, S]

  /** A field either fails with its match-type-computed error or constructs exactly one summary. */
  private transparent inline def fin[S <: Int, F, Anns](p: Parser[?]): FieldsRes =
    inline if constValue[FieldErr[S, Anns, IsOpt[F]] == ""] then ()
    else error(constValue[FieldErr[S, Anns, IsOpt[F]]])
    resOf[MarksOf[S, Anns]](
      List((p, constValue[IsOpt[F]], Annots.fieldAnnotsOf[Anns]))
    )

  /** One field: instance selection (the field's single implicit search) and shape dispatch — the
    * only parts that are inherently term-level. The dispatch must be an inline match on the
    * instance *tree*: a summonFrom binder's static type is the pattern type, so the precise subtype
    * is invisible to match types.
    */
  private transparent inline def fieldRes[F, Anns]: FieldsRes =
    summonFrom:
      case p: Parser[Unwrap[F]] =>
        inline p match
          case _: Parser.Value[?]        => fin[ValueShape, F, Anns](p)
          case _: Parser.ValuedFlag[?]   => fin[ValuedFlagShape, F, Anns](p)
          case _: Parser.Flag[?]         => fin[FlagShape, F, Anns](p)
          case _: Parser.Product[?]      => fin[ProductShape, F, Anns](p)
          case _: Parser.Repeated[?]     => fin[RepeatedShape, F, Anns](p)
          case _: Parser.Trailing[?]     => fin[TrailingShape, F, Anns](p)
          case _: Parser.CommandGroup[?] => fin[GroupShape, F, Anns](p)
          case _: Parser.Shared[?]       => fin[SharedShape, F, Anns](p)
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
        plainRes(List((summonInline[Parser[Unwrap[F]]], constValue[IsOpt[F]], FieldAnnots.empty)))

  /** The constant `@short` characters / `@name` names a slot claims, as tuples. */
  type ShortsOf[Anns] <: Tuple = Anns match
    case Ann[flagged.short, args, ?] *: t => Tuple.Head[args & NonEmptyTuple] *: ShortsOf[t]
    case _ *: t                           => ShortsOf[t]
    case EmptyTuple                       => EmptyTuple

  type LongsOf[Anns] <: Tuple = Anns match
    case Ann[flagged.name, args, ?] *: t => Tuple.Head[args & NonEmptyTuple] *: LongsOf[t]
    case _ *: t                          => LongsOf[t]
    case EmptyTuple                      => EmptyTuple

  /** Whether the two constant-name tuples share an element. */
  type OverlapsT[A <: Tuple, B <: Tuple] <: Boolean = B match
    case EmptyTuple => false
    case h *: t     =>
      Tuple.Contains[A, h] match
        case true  => true
        case false => OverlapsT[A, t]

  /** Duplicate constant names across the whole product, one fold over the annotation slots. A
    * field's constant names participate unless it is positional (positional fields claim no option
    * names); no shape knowledge is needed — a name annotation on a shape that could not claim it is
    * already a [[FieldErr]] error.
    */
  type SlotShorts[S] <: Tuple = HasAnnT[flagged.positional, S] match
    case true  => EmptyTuple
    case false => ShortsOf[S]

  type SlotLongs[S] <: Tuple = HasAnnT[flagged.positional, S] match
    case true  => EmptyTuple
    case false => LongsOf[S]

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

  /** Sum-level compile checks over the per-case annotation slots: at most one `@default` case, and
    * no duplicate constant command names (`@name` primaries and aliases) across cases.
    * Kebab-derived command-name collisions are value-level and checked at construction in
    * [[Assemble.sum]].
    */
  inline def checkSumRules[Slots]: Unit =
    inline erasedValue[Slots] match
      case _: EmptyTuple => ()
      case _: (s0 *: sr) =>
        inline if constValue[MultiDefaultT[s0 *: sr, false]] then
          error("only one @default command is supported")
        else ()
        inline if constValue[DupCaseNameErr[s0 *: sr] == ""] then ()
        else error(constValue[DupCaseNameErr[s0 *: sr]])

  /** Whether more than one case carries `@default`. */
  type MultiDefaultT[Slots, Seen <: Boolean] <: Boolean = Slots match
    case EmptyTuple => false
    case s *: t     =>
      HasAnnT[flagged.default, s] match
        case true =>
          Seen match
            case true  => true
            case false => MultiDefaultT[t, true]
        case false => MultiDefaultT[t, Seen]

  /** Duplicate constant command names across the sum's cases, one fold over the slots. */
  type DupCaseNameErr[Slots] = DupCaseNameErrAcc[Slots, EmptyTuple]

  type DupCaseNameErrAcc[Slots, Seen <: Tuple] <: String = Slots match
    case EmptyTuple => ""
    case s *: t     =>
      OverlapsT[Seen, LongsOf[s]] match
        case true  => "duplicate command name"
        case false => DupCaseNameErrAcc[t, Tuple.Concat[Seen, LongsOf[s]]]

  /** Whether annotation slot `Anns` contains an `A` — a compile-time constant. Match types rather
    * than inline-match recursion: reduction happens in the (cached) type domain instead of one
    * inline expansion per slot element.
    */
  type HasAnnT[A <: scala.annotation.Annotation, Anns] <: Boolean = Anns match
    case EmptyTuple        => false
    case Ann[A, ?, ?] *: _ => true
    case _ *: t            => HasAnnT[A, t]

  // ---- sums -----------------------------------------------------------------

  // Note: `case _: (h *: t)` binders widen term-singleton types (`L.A.type` becomes
  // `L`), which breaks `ValueOf` summoning for enum cases. `Tuple.Head`/`Tuple.Tail`
  // are match types and preserve the exact element type, so sums are traversed with
  // those instead.

  inline def entriesOf[T <: Tuple]: List[SubEntry] =
    inline erasedValue[T] match
      case _: EmptyTuple        => Nil
      case _: (? *: EmptyTuple) => entryOf[Tuple.Head[T & NonEmptyTuple]] :: Nil
      case _: NonEmptyTuple     =>
        entriesOf[Tuple.Take[T, HalfN[T]]] ::: entriesOf[Tuple.Drop[T, HalfN[T]]]

  /** One case of the sum being derived. Singleton and product cases belong to the sum's own
    * declaration and are handled in place; a case that is itself a sum is a separate hierarchy and
    * must provide its own `Parser` instance.
    */
  inline def entryOf[H]: SubEntry =
    summonFrom:
      case v: ValueOf[H]          => SubEntry.Leaf(v.value)
      case p: Parser[H]           => SubEntry.Node(() => p)
      case m: Mirror.ProductOf[H] => SubEntry.Node(() => caseCommand[H](using m))
      case _                      => SubEntry.Node(() => summonInline[Parser[H]])

  /** A product case of the sum: normally derived in place; a case whose sole field carries a full
    * `Parser.Command` embeds that command wholesale — substitution: the case's grammar *is* the
    * embedded command's (options, positionals, trailing and all — safe, because subcommand dispatch
    * delegates the remaining tokens rather than merging grammars), and the build wraps its result
    * in the case constructor. `@name`/`@help`/`@hidden` still apply on the *case*; `Shared` and
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
              "annotations have no effect on an embedded command field (put @name/@help/@hidden on the enum case)"
            )
        val c = summonInline[Parser.Command[E]]
        Parser.make[H](
          c.emapImpl(a => steps.result.Result.Ok(m.fromProduct(Tuple1(a)))),
          c.prog
        )

  // ---- singleton helpers ------------------------------------------------------

  inline def singletonValues[T <: Tuple]: List[Any] =
    inline erasedValue[T] match
      case _: EmptyTuple        => Nil
      case _: (? *: EmptyTuple) =>
        summonFrom:
          case v: ValueOf[Tuple.Head[T & NonEmptyTuple]] => v.value :: Nil
          case _                                         =>
            error(
              "Parser.Enumerated requires an enum (or sealed trait) whose cases are all parameterless"
            )
      case _: NonEmptyTuple =>
        singletonValues[Tuple.Take[T, HalfN[T]]] ::: singletonValues[Tuple.Drop[T, HalfN[T]]]
