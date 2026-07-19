package flagged.internal

import scala.compiletime.*
import scala.compiletime.ops.int./
import scala.compiletime.ops.int.{BitwiseOr, BitwiseAnd}
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
          arr => steps.result.Result.Ok(m.fromProduct(Tuple.fromArray(arr)))
        )
        Parser.make[A](cmd, Assemble.progName(constValue[m.MirroredLabel], onType))

  inline def sum[A](using m: Mirror.SumOf[A]): Parser.CommandGroup[A] =
    val annots = Annots.sumAnnots[A]
    val cmd = Assemble.sum(labelsOf[m.MirroredElemLabels], annots, entriesOf[m.MirroredElemTypes])
    Parser.makeGroup[A](cmd, Assemble.progName(constValue[m.MirroredLabel], annots.onType))

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
          case _: (s0 *: sr) => walk[t0 *: tr, s0 *: sr].fields

  /** Per-subtree summary of the field walk, carried in the *type* of a transparent inline result:
    * which special shapes the subtree contains, and the constant names it claims (as tuples, so a
    * merge can iterate them). Summaries are computed bottom-up and combined in [[merge]], where
    * every cross-field rule is checked — no state flows *into* a subtree, so the two halves of a
    * split are independent, type arguments stay small, and inline depth is logarithmic in the field
    * count.
    */
  final class FieldsRes(val fields: List[(Parser[?], Boolean, FieldAnnots)]):
    type Marks <: Int // bitmask: 1 trailing, 2 rep. positional, 4 positional, 8 subcommand, 16 names
    type Shorts <: Tuple // constant `@short` characters claimed by named options
    type Longs <: Tuple  // constant `@name` names (primary and aliases) claimed by named options

  final val TrailBit = 1
  final val RepBit   = 2
  final val PosBit   = 4
  final val GroupBit = 8
  final val NamesBit = 16 // the field claims constant names (@short / @name)

  type ResOf[M <: Int, Sh <: Tuple, Lo <: Tuple] =
    FieldsRes {
      type Marks  = M
      type Shorts = Sh
      type Longs  = Lo
    }

  inline def resOf[M <: Int, Sh <: Tuple, Lo <: Tuple](
      fields: List[(Parser[?], Boolean, FieldAnnots)]
  ): ResOf[M, Sh, Lo] =
    new FieldsRes(fields).asInstanceOf[ResOf[M, Sh, Lo]]

  /** A plain named option's summary: nothing special, no constant names. */
  inline def plainRes(fields: List[(Parser[?], Boolean, FieldAnnots)]) =
    resOf[0, EmptyTuple, EmptyTuple](fields)

  transparent inline def isZero[M <: Int]: Boolean =
    inline erasedValue[M] match
      case _: 0 => true
      case _    => false

  transparent inline def hasBit[M <: Int, B <: Int]: Boolean =
    inline erasedValue[BitwiseAnd[M, B]] match
      case _: 0 => false
      case _    => true

  type HalfN[T <: Tuple] = Tuple.Size[T] / 2

  /** Read a type-level boolean via type-test rather than `constValue`: refined members behind
    * inferred type parameters reduce here where `constValue` cannot.
    */
  transparent inline def isTrue[B <: Boolean]: Boolean =
    inline erasedValue[B] match
      case _: true  => true
      case _: false => false

  /** Whether the field's annotation slot is empty — the common case; lets every per-field
    * annotation check and name collection collapse to nothing.
    */
  transparent inline def noAnns[Anns]: Boolean =
    inline erasedValue[Anns] match
      case _: EmptyTuple => true
      case _             => false

  transparent inline def walk[Types <: Tuple, Slots <: Tuple]: FieldsRes =
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
  transparent inline def merge[L <: FieldsRes, R <: FieldsRes](l: L, r: R): FieldsRes =
    inline if isZero[BitwiseOr[l.Marks, r.Marks]] then () // one gate: reduction is reused below
    else crossChecks(l, r)
    resOf[
      BitwiseOr[l.Marks, r.Marks],
      Tuple.Concat[l.Shorts, r.Shorts],
      Tuple.Concat[l.Longs, r.Longs]
    ](l.fields ++ r.fields)

  /** Four-way merge for unrolled leaf groups: the all-plain fast path is a single gate; anything
    * special delegates to nested pairwise merges (identical semantics: the summary is associative
    * and every check is pairwise).
    */
  transparent inline def merge4[
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
  transparent inline def crossChecks[L <: FieldsRes, R <: FieldsRes](l: L, r: R): Unit =
    inline if hasBit[l.Marks, NamesBit.type] then
      inline if hasBit[r.Marks, NamesBit.type] then
        checkDisjointShorts[l.Shorts, r.Shorts]
        checkDisjointLongs[l.Longs, r.Longs]
      else ()
    else ()
    inline if hasBit[l.Marks, TrailBit.type] then
      inline if hasBit[r.Marks, TrailBit.type] then
        error("only one trailing field is supported per command")
      else ()
    else ()
    inline if hasBit[l.Marks, GroupBit.type] then
      inline if hasBit[r.Marks, GroupBit.type] then
        error("only one subcommand field is supported per command")
      else inline if hasBit[r.Marks, PosBit.type] then
        error("mixing positional fields with a subcommand field is ambiguous and not supported")
      else ()
    else ()
    inline if hasBit[r.Marks, GroupBit.type] then
      inline if hasBit[l.Marks, PosBit.type] then
        error("mixing positional fields with a subcommand field is ambiguous and not supported")
      else ()
    else ()
    inline if hasBit[l.Marks, RepBit.type] then
      inline if hasBit[r.Marks, PosBit.type] then
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

  /** One field: annotation-only checks, instance selection (the field's single implicit search),
    * and dispatch on the instance's statically known shape. The field's extracted [[FieldAnnots]]
    * ride along in the value, so no separate annotation walk is needed.
    */
  transparent inline def fieldRes[F, Anns]: FieldsRes =
    inline if noAnns[Anns] then () // nothing to check, and none of the cascades below expand
    else
      inline if hasAnnApplied[Ann[flagged.short, 'h' *: EmptyTuple, ?], Anns] then
        error("short option 'h' is reserved for help")
      else ()
      inline if hasAnn[flagged.version, Anns] then
        error("@version has no effect on a field (put it on the top-level type)")
      else ()
      inline if hasAnn[flagged.default, Anns] then
        error("@default has no effect on a field (put it on a command-group enum case)")
      else ()
      inline if hasAnn[flagged.positional, Anns] then
        inline if hasAnn[flagged.hidden, Anns] then
          error("@hidden cannot be combined with @positional")
        else inline if hasAnn[flagged.group, Anns] then
          error("@group cannot be combined with @positional")
        else inline if hasAnn[flagged.short, Anns] then
          error("@short cannot be combined with @positional")
        else ()
      else inline if hasAnnApplied[Ann[flagged.name, "help" *: EmptyTuple, ?], Anns] then
        error("option name 'help' is reserved")
      else ()
    summonFrom:
      case p: Parser[Unwrap[F]] =>
        inline p match
          case _: Parser.Value[?] =>
            inline if noAnns[Anns] then plainRes(List((p, constValue[IsOpt[F]], FieldAnnots.empty)))
            else namedRes[Anns](p, constValue[IsOpt[F]])
          case _: Parser.ValuedFlag[?] =>
            inline if noAnns[Anns] then plainRes(List((p, constValue[IsOpt[F]], FieldAnnots.empty)))
            else namedRes[Anns](p, constValue[IsOpt[F]])
          case _: Parser.Flag[?] =>
            inline if constValue[IsOpt[F]] then
              error("a flag Parser without a value parser cannot be used inside Option")
            else inline if hasAnn[flagged.positional, Anns] then
              error("a flag Parser without a value parser cannot be used positionally")
            else namedRes[Anns](p, constValue[IsOpt[F]])
          case _: Parser.Repeated[?] =>
            inline if constValue[IsOpt[F]] then
              error(
                "Option of a repeated Parser is not supported: the plain type is empty when absent"
              )
            else inline if hasAnn[flagged.positional, Anns] then
              // rep-pos + pos
              resOf[6, EmptyTuple, EmptyTuple](List((p, false, Annots.fieldAnnotsOf[Anns])))
            else namedRes[Anns](p, constValue[IsOpt[F]])
          case _: Parser.Trailing[?] =>
            inline if hasAnn[flagged.positional, Anns] then
              error("@positional cannot be combined with a trailing field")
            else inline if hasAnn[flagged.short, Anns] then
              error("@short cannot be combined with a trailing field")
            else inline if hasAnn[flagged.name, Anns] then
              error("@name has no effect on a trailing field")
            else inline if hasAnn[flagged.hidden, Anns] then
              error("@hidden has no effect on a trailing field")
            else inline if hasAnn[flagged.group, Anns] then
              error("@group has no effect on a trailing field")
            else // trailing
              resOf[1, EmptyTuple, EmptyTuple](
                List((p, constValue[IsOpt[F]], Annots.fieldAnnotsOf[Anns]))
              )
          case _: Parser.CommandGroup[?] =>
            inline if hasAnn[flagged.positional, Anns] then
              error("@positional cannot be combined with a subcommand field")
            else inline if hasAnn[flagged.short, Anns] then
              error("@short has no effect on a subcommand field")
            else inline if hasAnn[flagged.name, Anns] then
              error("@name has no effect on a subcommand field (command names come from the cases)")
            else inline if hasAnn[flagged.help, Anns] then
              error("@help has no effect on a subcommand field (put it on the enum or its cases)")
            else inline if hasAnn[flagged.hidden, Anns] then
              error("@hidden has no effect on a subcommand field (put it on the enum cases)")
            else inline if hasAnn[flagged.group, Anns] then
              error("@group has no effect on a subcommand field")
            else // subcommand group
              resOf[8, EmptyTuple, EmptyTuple](
                List((p, constValue[IsOpt[F]], Annots.fieldAnnotsOf[Anns]))
              )
          case _: Parser.Command[?] =>
            inline if hasAnn[flagged.positional, Anns] then
              error("@positional cannot be combined with a command-shaped Parser")
            else inline if hasAnn[flagged.short, Anns] then
              error("@short has no effect on a spliced options group")
            else inline if hasAnn[flagged.help, Anns] then
              error("@help has no effect on a spliced options group")
            else inline if hasAnn[flagged.hidden, Anns] then
              error(
                "@hidden has no effect on a spliced options group (put it on the group's fields)"
              )
            else plainRes(List((p, constValue[IsOpt[F]], Annots.fieldAnnotsOf[Anns])))
          case _ =>
            error(
              "the shape of this field's Parser is not statically known: give the given a shape type such as Parser.Value[X], or build it with the Parser constructors / derivation clauses"
            )
      case _ =>
        // fails with Parser's missing-instance guidance
        plainRes(List((summonInline[Parser[Unwrap[F]]], constValue[IsOpt[F]], FieldAnnots.empty)))

  /** A field that surely becomes a named option: claim its constant names (if not positional). */
  transparent inline def namedRes[Anns](p: Parser[?], inline optional: Boolean): FieldsRes =
    inline if noAnns[Anns] then plainRes(List((p, optional, FieldAnnots.empty)))
    else inline if hasAnn[flagged.positional, Anns] then
      resOf[4, EmptyTuple, EmptyTuple](List((p, optional, Annots.fieldAnnotsOf[Anns]))) // pos
    else
      resOf[NamesBit.type, ShortsOf[Anns], LongsOf[Anns]](
        List((p, optional, Annots.fieldAnnotsOf[Anns]))
      )

  /** The constant `@short` characters / `@name` names a slot claims, as tuples. */
  type ShortsOf[Anns] <: Tuple = Anns match
    case Ann[flagged.short, args, ?] *: t => Tuple.Head[args & NonEmptyTuple] *: ShortsOf[t]
    case _ *: t                           => ShortsOf[t]
    case EmptyTuple                       => EmptyTuple

  type LongsOf[Anns] <: Tuple = Anns match
    case Ann[flagged.name, args, ?] *: t => Tuple.Head[args & NonEmptyTuple] *: LongsOf[t]
    case _ *: t                          => LongsOf[t]
    case EmptyTuple                      => EmptyTuple

  /** The union of a tuple's elements, for membership-as-subtyping tests. */
  type FoldU[T <: Tuple] = T match
    case EmptyTuple => Nothing
    case h *: t     => h | FoldU[t]

  inline def checkDisjointShorts[A <: Tuple, B <: Tuple]: Unit =
    inline erasedValue[A] match
      case _: EmptyTuple => ()
      case _             =>
        inline erasedValue[B] match
          case _: EmptyTuple => ()
          case _: (h *: t)   =>
            inline erasedValue[h] match
              case _: FoldU[A] => error("duplicate short option")
              case _           => checkDisjointShorts[A, t]

  inline def checkDisjointLongs[A <: Tuple, B <: Tuple]: Unit =
    inline erasedValue[A] match
      case _: EmptyTuple => ()
      case _             =>
        inline erasedValue[B] match
          case _: EmptyTuple => ()
          case _: (h *: t)   =>
            inline erasedValue[h] match
              case _: FoldU[A] => error("duplicate option name")
              case _           => checkDisjointLongs[A, t]

  /** Whether annotation slot `Anns` contains an `A` — a compile-time constant. */
  transparent inline def hasAnn[A <: scala.annotation.Annotation, Anns]: Boolean =
    inline erasedValue[Anns] match
      case _: EmptyTuple          => false
      case _: (Ann[A, ?, ?] *: _) => true
      case _: (_ *: t)            => hasAnn[A, t]

  /** Whether the slot contains a specific annotation *application*, e.g. `@short('h')` — decidable
    * because `Ann` is invariant with constant arguments.
    */
  transparent inline def hasAnnApplied[Applied, Anns]: Boolean =
    inline erasedValue[Anns] match
      case _: EmptyTuple     => false
      case _: (Applied *: _) => true
      case _: (_ *: t)       => hasAnnApplied[Applied, t]

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
