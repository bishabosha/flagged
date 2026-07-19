package flagged.internal

import scala.compiletime.*
import scala.compiletime.ops.int./
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
        val annots = Annots.makeProduct[A](
          Annots.targetAnnotsOf[am.MirroredSelfAnnotations],
          Annots.fieldAnnotsEach[am.MirroredAnnotations]
        )
        val cmd = Assemble.product(
          labelsOf[m.MirroredElemLabels],
          fieldsOf[m.MirroredElemTypes, am.MirroredAnnotations, m.MirroredElemLabels],
          Defaults.derived[A],
          annots,
          arr => steps.result.Result.Ok(m.fromProduct(Tuple.fromArray(arr)))
        )
        Parser.make[A](cmd, Assemble.progName(constValue[m.MirroredLabel], annots.onType))

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
    constValueTuple[L].toList.asInstanceOf[List[String]]

  /** The single field rule: summon the field type's `Parser`; `Option[_]` marks it optional. The
    * parser's shape (its `Parser` subtype, which derivation requires to be statically known)
    * decides everything else; combinations visible in types are rejected here, at compile time.
    */
  inline def fieldsOf[Types <: Tuple, Slots <: Tuple, Labels <: Tuple]: List[(Parser[?], Boolean)] =
    // one destructuring match per tuple so the walk's match types (`Take`/`Drop`/`Size`) operate
    // on concrete tuple types: the mirror members arrive as abstract paths (`am.MirroredAnnotations`)
    // that inline-match reduction resolves but match-type reduction alone does not
    inline erasedValue[Types] match
      case _: EmptyTuple => Nil
      case _: (t0 *: tr) =>
        inline erasedValue[Slots] match
          case _: (s0 *: sr) =>
            inline erasedValue[Labels] match
              case _: (l0 *: lr) =>
                walk[
                  t0 *: tr,
                  s0 *: sr,
                  l0 *: lr,
                  false,
                  false,
                  false,
                  false,
                  Nothing,
                  Nothing
                ].fields

  /** Compile-time state of the field walk, carried in the *type* of a transparent inline result:
    * the trailing / repeated-positional / subcommand / positional markers, and the unions of
    * claimed constant names (membership tested as subtyping). Carrying it in the result type lets
    * [[walk]] split the field tuple in halves — the left half's result type supplies the right
    * half's type arguments — so inline depth grows with the logarithm of the field count instead of
    * the count itself, while the checks stay strictly left-to-right.
    */
  final class FieldsRes(val fields: List[(Parser[?], Boolean)]):
    type SeenTrailing <: Boolean
    type SeenRepeatedPos <: Boolean
    type SeenGroup <: Boolean
    type SeenPositional <: Boolean
    type Shorts
    type Longs

  type ResOf[ST <: Boolean, SR <: Boolean, SG <: Boolean, SP <: Boolean, Sh, Lo] =
    FieldsRes {
      type SeenTrailing    = ST
      type SeenRepeatedPos = SR
      type SeenGroup       = SG
      type SeenPositional  = SP
      type Shorts          = Sh
      type Longs           = Lo
    }

  inline def resOf[ST <: Boolean, SR <: Boolean, SG <: Boolean, SP <: Boolean, Sh, Lo](
      fields: List[(Parser[?], Boolean)]
  ): ResOf[ST, SR, SG, SP, Sh, Lo] =
    new FieldsRes(fields).asInstanceOf[ResOf[ST, SR, SG, SP, Sh, Lo]]

  type HalfN[T <: Tuple] = Tuple.Size[T] / 2

  /** Read a type-level boolean via type-test rather than `constValue`: the walk's state types
    * arrive as pattern-bound variables that inline-match reduction resolves where `constValue`
    * cannot.
    */
  transparent inline def isTrue[B <: Boolean]: Boolean =
    inline erasedValue[B] match
      case _: true  => true
      case _: false => false

  transparent inline def walk[
      Types <: Tuple,
      Slots <: Tuple,
      Labels <: Tuple,
      ST <: Boolean,
      SR <: Boolean,
      SG <: Boolean,
      SP <: Boolean,
      Shorts,
      Longs
  ]: FieldsRes =
    inline erasedValue[Types] match
      case _: EmptyTuple        => resOf[ST, SR, SG, SP, Shorts, Longs](Nil)
      case _: (? *: EmptyTuple) =>
        one[
          Tuple.Head[Types & NonEmptyTuple],
          Tuple.Head[Slots & NonEmptyTuple],
          Tuple.Head[Labels & NonEmptyTuple],
          ST,
          SR,
          SG,
          SP,
          Shorts,
          Longs
        ]
      case _: NonEmptyTuple =>
        walk[
          Tuple.Take[Types, HalfN[Types]],
          Tuple.Take[Slots, HalfN[Types]],
          Tuple.Take[Labels, HalfN[Types]],
          ST,
          SR,
          SG,
          SP,
          Shorts,
          Longs
        ].andRight[Types, Slots, Labels]

  /** Continuations for the split: the receiver / parameter types are inferred as the arguments'
    * *precise* refined types (a `val` or pattern binder would fix the type before expansion, losing
    * the refinement), so the right half reads the left half's final state off the left result's
    * type members.
    */
  extension [L <: FieldsRes](l: L)
    transparent inline def andRight[Types <: Tuple, Slots <: Tuple, Labels <: Tuple]: FieldsRes =
      afterRight(
        l,
        walk[
          Tuple.Drop[Types, HalfN[Types]],
          Tuple.Drop[Slots, HalfN[Types]],
          Tuple.Drop[Labels, HalfN[Types]],
          l.SeenTrailing,
          l.SeenRepeatedPos,
          l.SeenGroup,
          l.SeenPositional,
          l.Shorts,
          l.Longs
        ]
      )

  transparent inline def afterRight[L <: FieldsRes, R <: FieldsRes](l: L, r: R): FieldsRes =
    resOf[r.SeenTrailing, r.SeenRepeatedPos, r.SeenGroup, r.SeenPositional, r.Shorts, r.Longs](
      l.fields ++ r.fields
    )

  /** One field: annotation-only checks, `Option` unwrapping, then shape dispatch. */
  transparent inline def one[
      F,
      Anns,
      L,
      ST <: Boolean,
      SR <: Boolean,
      SG <: Boolean,
      SP <: Boolean,
      Shorts,
      Longs
  ]: FieldsRes =
    inline if hasAnnApplied[Ann[flagged.short, 'h' *: EmptyTuple, ?], Anns] then
      error("short option 'h' is reserved for help")
    else ()
    inline if hasAnn[flagged.positional, Anns] then
      inline if hasAnn[flagged.short, Anns] then error("@short cannot be combined with @positional")
      else inline if isTrue[SR] then
        error("a repeated positional must be the last positional field")
      else inline if isTrue[SG] then
        error("mixing positional fields with a subcommand field is ambiguous and not supported")
      else ()
    else inline if hasAnnApplied[Ann[flagged.name, "help" *: EmptyTuple, ?], Anns] then
      error("option name 'help' is reserved")
    else ()
    inline erasedValue[F] match
      case _: Option[e] => shape[e, Anns, L, ST, SR, SG, SP, Shorts, Longs](optional = true)
      case _            => shape[F, Anns, L, ST, SR, SG, SP, Shorts, Longs](optional = false)

  /** Select the instance (the field's single implicit search) and dispatch on its statically known
    * shape for the shape x annotation checks and name folding.
    */
  transparent inline def shape[
      E,
      Anns,
      L,
      ST <: Boolean,
      SR <: Boolean,
      SG <: Boolean,
      SP <: Boolean,
      Shorts,
      Longs
  ](inline optional: Boolean): FieldsRes =
    summonFrom:
      case p: Parser[E] =>
        inline p match
          case _: Parser.ValuedFlag[?] =>
            namedRes[Anns, L, ST, SR, SG, SP, Shorts, Longs](p, optional)
          case _: Parser.Flag[?] =>
            inline if optional then
              error("a flag Parser without a value parser cannot be used inside Option")
            else inline if hasAnn[flagged.positional, Anns] then
              error("a flag Parser without a value parser cannot be used positionally")
            else namedRes[Anns, L, ST, SR, SG, SP, Shorts, Longs](p, optional)
          case _: Parser.Value[?] =>
            namedRes[Anns, L, ST, SR, SG, SP, Shorts, Longs](p, optional)
          case _: Parser.Repeated[?] =>
            inline if optional then
              error(
                "Option of a repeated Parser is not supported: the plain type is empty when absent"
              )
            else inline if hasAnn[flagged.positional, Anns] then
              resOf[ST, true, SG, true, Shorts, Longs](List((p, optional)))
            else namedRes[Anns, L, ST, SR, SG, SP, Shorts, Longs](p, optional)
          case _: Parser.Trailing[?] =>
            inline if isTrue[ST] then error("only one trailing field is supported per command")
            else inline if hasAnn[flagged.positional, Anns] then
              error("@positional cannot be combined with a trailing field")
            else inline if hasAnn[flagged.short, Anns] then
              error("@short cannot be combined with a trailing field")
            else inline if hasAnn[flagged.name, Anns] then
              error("@name has no effect on a trailing field")
            else resOf[true, SR, SG, SP, Shorts, Longs](List((p, optional)))
          case _: Parser.CommandGroup[?] =>
            inline if hasAnn[flagged.positional, Anns] then
              error("@positional cannot be combined with a subcommand field")
            else inline if hasAnn[flagged.short, Anns] then
              error("@short has no effect on a subcommand field")
            else inline if hasAnn[flagged.name, Anns] then
              error("@name has no effect on a subcommand field (command names come from the cases)")
            else inline if hasAnn[flagged.help, Anns] then
              error("@help has no effect on a subcommand field (put it on the enum or its cases)")
            else inline if isTrue[SG] then
              error("only one subcommand field is supported per command")
            else inline if isTrue[SP] then
              error(
                "mixing positional fields with a subcommand field is ambiguous and not supported"
              )
            else resOf[ST, SR, true, SP, Shorts, Longs](List((p, optional)))
          case _: Parser.Command[?] =>
            inline if hasAnn[flagged.positional, Anns] then
              error("@positional cannot be combined with a command-shaped Parser")
            else inline if optional then error("Option of a spliced options group is not supported")
            else inline if hasAnn[flagged.short, Anns] then
              error("@short has no effect on a spliced options group")
            else inline if hasAnn[flagged.name, Anns] then
              error(
                "@name has no effect on a spliced options group (its options keep their own names)"
              )
            else inline if hasAnn[flagged.help, Anns] then
              error("@help has no effect on a spliced options group")
            else resOf[ST, SR, SG, SP, Shorts, Longs](List((p, optional)))
          case _ =>
            error(
              "the shape of this field's Parser is not statically known: give the given a shape type such as Parser.Value[X], or build it with the Parser constructors / derivation clauses"
            )
      case _ =>
        // fails with Parser's missing-instance guidance
        resOf[ST, SR, SG, SP, Shorts, Longs](List((summonInline[Parser[E]], optional)))

  /** A field that surely becomes a named option: fold its constant names (if not positional) into
    * the claimed-name unions, testing membership first.
    */
  transparent inline def namedRes[
      Anns,
      L,
      ST <: Boolean,
      SR <: Boolean,
      SG <: Boolean,
      SP <: Boolean,
      Shorts,
      Longs
  ](p: Parser[?], inline optional: Boolean): FieldsRes =
    inline if hasAnn[flagged.positional, Anns] then
      resOf[ST, SR, SG, true, Shorts, Longs](List((p, optional)))
    else
      checkNewShort[Anns, Shorts]
      checkNewLong[Anns, L, Longs]
      resOf[ST, SR, SG, SP, Shorts | ShortIn[Anns], Longs | EffLong[Anns, L]](
        List((p, optional))
      )

  /** The `@short` character claimed by the slot, or `Nothing`. */
  type ShortIn[Anns] = Anns match
    case Ann[flagged.short, args, ?] *: _ => Tuple.Head[args & NonEmptyTuple]
    case _ *: t                           => ShortIn[t]
    case EmptyTuple                       => Nothing

  /** The long name the field claims when written as a constant (`@name`); `Nothing` for
    * label-derived names, which involve kebab-casing and are checked at construction instead.
    */
  type EffLong[Anns, L] = Anns match
    case Ann[flagged.name, args, ?] *: _ => Tuple.Head[args & NonEmptyTuple]
    case _ *: t                          => EffLong[t, L]
    case EmptyTuple                      => Nothing

  inline def checkNewShort[Anns, Shorts]: Unit =
    inline if hasAnn[flagged.short, Anns] then
      inline erasedValue[ShortIn[Anns]] match
        case _: Shorts => error("duplicate short option")
        case _         => ()
    else ()

  inline def checkNewLong[Anns, L, Longs]: Unit =
    inline if hasAnn[flagged.name, Anns] then
      inline erasedValue[EffLong[Anns, L]] match
        case _: Longs => error("duplicate option name")
        case _        => ()
    else ()

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
      case _: EmptyTuple    => Nil
      case _: NonEmptyTuple =>
        entryOf[Tuple.Head[T & NonEmptyTuple]] :: entriesOf[Tuple.Tail[T & NonEmptyTuple]]

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
      case _: EmptyTuple    => Nil
      case _: NonEmptyTuple =>
        summonFrom:
          case v: ValueOf[Tuple.Head[T & NonEmptyTuple]] =>
            v.value :: singletonValues[Tuple.Tail[T & NonEmptyTuple]]
          case _ =>
            error(
              "Parser.Enumerated requires an enum (or sealed trait) whose cases are all parameterless"
            )
