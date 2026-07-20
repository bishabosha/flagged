package flagged.internal

import scala.compiletime.*
import scala.compiletime.ops.int./
import scala.compiletime.ops.int.{BitwiseOr, BitwiseAnd}
import scala.compiletime.ops.any.{==, !=}
import scala.deriving.Mirror
import flagged.Parser
import flagged.meta.{Ann, AnnotMirror, Defaults}

/** `Mirror`-based derivation. Structure and construction come from `Mirror`; field semantics are
  * the field parser's schema: command-shaped instances become nested subcommands (sums) or spliced
  * option groups (products), value shapes parse as option or positional values. Nothing is derived
  * across type boundaries — each enum or options group in a command tree provides its own instance.
  *
  * The only macro-backed pieces are [[Defaults]] (term-level: default values are arbitrary
  * expressions) and [[flagged.meta.AnnotMirror]] (type-level: annotations reduced to singleton
  * types, extracted here via [[flagged.meta.AnnotMirror.find]] into typed [[Annots]]).
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
          arr => steps.result.Result.Ok(m.fromProduct(Tuple.fromArray(arr))),
          versionOf[A, am.MirroredSelfAnnotations]
        )
        Parser.make[A](cmd, Assemble.progName(constValue[m.MirroredLabel], onType))

  inline def sum[A](using m: Mirror.SumOf[A]): Parser.CommandGroup[A] =
    summonFrom:
      case am: AnnotMirror.Sum[A] =>
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

  type NoMarks  = 0
  type TrailBit = 1
  type RepBit   = 2
  type PosBit   = 4
  type GroupBit = 8

  // shape codes for the field dispatch
  type ValueShape      = 1
  type FlagShape       = 2
  type ValuedFlagShape = 3
  type RepeatedShape   = 4
  type TrailingShape   = 5
  type CommandShape    = 6 // a spliced options group
  type GroupShape      = 7 // a subcommand group

  type ResOf[M <: Int] = FieldsRes { type Marks = M }

  inline def resOf[M <: Int](
      fields: List[(Parser[?], Boolean, FieldAnnots)]
  ): ResOf[M] =
    new FieldsRes(fields).asInstanceOf[ResOf[M]]

  /** A plain named option's summary: nothing special. */
  inline def plainRes(fields: List[(Parser[?], Boolean, FieldAnnots)]) =
    resOf[NoMarks](fields)

  inline def isZero[M <: Int]: Boolean           = constValue[M == 0]
  inline def hasBit[M <: Int, B <: Int]: Boolean =
    constValue[BitwiseAnd[M, B] != 0]

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
    inline if isZero[BitwiseOr[l.Marks, r.Marks]] then () // one gate: reduction is reused below
    else crossChecks(l, r)
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

  /** Only expanded when both sides contain a special shape (a dropped inline-if branch never
    * expands): each cross-field rule involves two fields, one in each half at exactly one merge.
    */
  private transparent inline def crossChecks[L <: FieldsRes, R <: FieldsRes](l: L, r: R): Unit =
    inline if hasBit[l.Marks, TrailBit] then
      inline if hasBit[r.Marks, TrailBit] then
        error("only one trailing field is supported per command")
      else ()
    else ()
    inline if hasBit[l.Marks, GroupBit] then
      inline if hasBit[r.Marks, GroupBit] then
        error("only one subcommand field is supported per command")
      else inline if hasBit[r.Marks, PosBit] then
        error("mixing positional fields with a subcommand field is ambiguous and not supported")
      else ()
    else ()
    inline if hasBit[r.Marks, GroupBit] then
      inline if hasBit[l.Marks, PosBit] then
        error("mixing positional fields with a subcommand field is ambiguous and not supported")
      else ()
    else ()
    inline if hasBit[l.Marks, RepBit] then
      inline if hasBit[r.Marks, PosBit] then
        error("a repeated positional must be the last positional field")
      else ()
    else ()

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
  type FieldErr[S <: Int, Anns, Opt <: Boolean] <: String =
    HasAppliedT[Ann[flagged.short, 'h' *: EmptyTuple, ?], Anns] match
      case true  => "short option 'h' is reserved for help"
      case false =>
        HasAnnT[flagged.version, Anns] match
          case true  => "@version has no effect on a field (put it on the top-level type)"
          case false =>
            HasAnnT[flagged.default, Anns] match
              case true => "@default has no effect on a field (put it on a command-group enum case)"
              case false =>
                HasAnnT[flagged.positional, Anns] match
                  case true =>
                    HasAnnT[flagged.hidden, Anns] match
                      case true  => "@hidden cannot be combined with @positional"
                      case false =>
                        HasAnnT[flagged.group, Anns] match
                          case true  => "@group cannot be combined with @positional"
                          case false =>
                            HasAnnT[flagged.short, Anns] match
                              case true  => "@short cannot be combined with @positional"
                              case false => ShapeErr[S, Anns, Opt]
                  case false =>
                    HasAppliedT[Ann[flagged.name, "help" *: EmptyTuple, ?], Anns] match
                      case true  => "option name 'help' is reserved"
                      case false => ShapeErr[S, Anns, Opt]

  type ShapeErr[S <: Int, Anns, Opt <: Boolean] <: String = S match
    case ValueShape      => ""
    case ValuedFlagShape => ""
    case FlagShape       =>
      Opt match
        case true  => "a flag Parser without a value parser cannot be used inside Option"
        case false =>
          HasAnnT[flagged.positional, Anns] match
            case true  => "a flag Parser without a value parser cannot be used positionally"
            case false => ""
    case RepeatedShape =>
      Opt match
        case true =>
          "Option of a repeated Parser is not supported: the plain type is empty when absent"
        case false => ""
    case 5 =>
      HasAnnT[flagged.positional, Anns] match
        case true  => "@positional cannot be combined with a trailing field"
        case false =>
          HasAnnT[flagged.short, Anns] match
            case true  => "@short cannot be combined with a trailing field"
            case false =>
              HasAnnT[flagged.name, Anns] match
                case true  => "@name has no effect on a trailing field"
                case false =>
                  HasAnnT[flagged.hidden, Anns] match
                    case true  => "@hidden has no effect on a trailing field"
                    case false =>
                      HasAnnT[flagged.group, Anns] match
                        case true  => "@group has no effect on a trailing field"
                        case false => ""
    case GroupShape =>
      HasAnnT[flagged.positional, Anns] match
        case true  => "@positional cannot be combined with a subcommand field"
        case false =>
          HasAnnT[flagged.short, Anns] match
            case true  => "@short has no effect on a subcommand field"
            case false =>
              HasAnnT[flagged.name, Anns] match
                case true =>
                  "@name has no effect on a subcommand field (command names come from the cases)"
                case false =>
                  HasAnnT[flagged.help, Anns] match
                    case true =>
                      "@help has no effect on a subcommand field (put it on the enum or its cases)"
                    case false =>
                      HasAnnT[flagged.hidden, Anns] match
                        case true =>
                          "@hidden has no effect on a subcommand field (put it on the enum cases)"
                        case false =>
                          HasAnnT[flagged.group, Anns] match
                            case true  => "@group has no effect on a subcommand field"
                            case false => ""
    case CommandShape =>
      HasAnnT[flagged.positional, Anns] match
        case true  => "@positional cannot be combined with a command-shaped Parser"
        case false =>
          HasAnnT[flagged.short, Anns] match
            case true  => "@short has no effect on a spliced options group"
            case false =>
              HasAnnT[flagged.help, Anns] match
                case true  => "@help has no effect on a spliced options group"
                case false =>
                  HasAnnT[flagged.hidden, Anns] match
                    case true =>
                      "@hidden has no effect on a spliced options group (put it on the group's fields)"
                    case false => ""

  /** The [[FieldsRes.Marks]] contribution of a field of shape `S` with annotations `Anns`. */
  type MarksOf[S <: Int, Anns] <: Int = S match
    case TrailingShape => TrailBit
    case GroupShape    => GroupBit
    case CommandShape  => NoMarks
    case _             =>
      HasAnnT[flagged.positional, Anns] match
        case true =>
          S match
            case RepeatedShape => BitwiseOr[RepBit, PosBit]
            case _             => PosBit
        case false => NoMarks

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
          case _: Parser.Repeated[?]     => fin[RepeatedShape, F, Anns](p)
          case _: Parser.Trailing[?]     => fin[TrailingShape, F, Anns](p)
          case _: Parser.CommandGroup[?] => fin[GroupShape, F, Anns](p)
          case _: Parser.Command[?]      => fin[CommandShape, F, Anns](p)
          case _                         =>
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

  /** Whether annotation slot `Anns` contains an `A` — a compile-time constant. Match types rather
    * than inline-match recursion: reduction happens in the (cached) type domain instead of one
    * inline expansion per slot element.
    */
  type HasAnnT[A <: scala.annotation.Annotation, Anns] <: Boolean = Anns match
    case EmptyTuple        => false
    case Ann[A, ?, ?] *: _ => true
    case _ *: t            => HasAnnT[A, t]

  /** Whether the slot contains a specific annotation *application*, e.g. `@short('h')` — decidable
    * because `Ann` is invariant with constant arguments.
    */
  type HasAppliedT[Applied, Anns] <: Boolean = Anns match
    case EmptyTuple   => false
    case Applied *: _ => true
    case _ *: t       => HasAppliedT[Applied, t]

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
      case m: Mirror.ProductOf[H] => SubEntry.Node(() => product[H](using m))
      case _                      => SubEntry.Node(() => summonInline[Parser[H]])

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
