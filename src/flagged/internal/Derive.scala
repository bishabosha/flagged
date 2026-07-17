package flagged.internal

import scala.compiletime.*
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
    * parser's schema decides everything else at assembly — except that combinations already visible
    * in types (the field's annotations, `Option` wrapping, and the shape of shape-refined
    * instances, see [[Parser.Aux]]) are rejected here, at compile time. Shape-erased instances fall
    * back to construction-time validation.
    */
  inline def fieldsOf[Types <: Tuple, Slots <: Tuple, Labels <: Tuple]: List[(Parser[?], Boolean)] =
    fieldsRec[Types, Slots, Labels, Nothing, Nothing](
      seenTrailing = false,
      seenRepeatedPos = false,
      seenGroup = false,
      seenPositional = false
    )

  /** The field walk. Each field costs exactly one implicit search (the instance selection); every
    * compile-time question — annotation combinations, the shape of the found instance,
    * duplicate-name membership — is answered by inline matches over types already at hand.
    * Cross-field state travels as inline booleans and as union types (claimed constant names,
    * membership tested as subtyping). A field whose instance's shape cannot be determined
    * statically is rejected: ascribe custom givens to `Parser.Aux[...]` (or build them without an
    * ascription).
    */
  inline def fieldsRec[Types <: Tuple, Slots <: Tuple, Labels <: Tuple, Shorts, Longs](
      inline seenTrailing: Boolean,
      inline seenRepeatedPos: Boolean,
      inline seenGroup: Boolean,
      inline seenPositional: Boolean
  ): List[(Parser[?], Boolean)] =
    inline erasedValue[Types] match
      case _: EmptyTuple => Nil
      case _: (f *: ft)  =>
        inline erasedValue[Slots] match
          case _: (a *: at) =>
            inline erasedValue[Labels] match
              case _: (l *: lt) =>
                // annotation-only checks need no instance
                inline if hasAnnApplied[Ann[flagged.short, 'h' *: EmptyTuple, ?], a] then
                  error("short option 'h' is reserved for help")
                else ()
                inline if hasAnn[flagged.positional, a] then
                  inline if hasAnn[flagged.short, a] then
                    error("@short cannot be combined with @positional")
                  else inline if seenRepeatedPos then
                    error("a repeated positional must be the last positional field")
                  else inline if seenGroup then
                    error(
                      "mixing positional fields with a subcommand field is ambiguous and not supported"
                    )
                  else ()
                else inline if hasAnnApplied[Ann[flagged.name, "help" *: EmptyTuple, ?], a] then
                  error("option name 'help' is reserved")
                else ()
                inline erasedValue[f] match
                  case _: Option[e] =>
                    step[e, ft, at, lt, a, l, Shorts, Longs](
                      optional = true,
                      seenTrailing = seenTrailing,
                      seenRepeatedPos = seenRepeatedPos,
                      seenGroup = seenGroup,
                      seenPositional = seenPositional
                    )
                  case _ =>
                    step[f, ft, at, lt, a, l, Shorts, Longs](
                      optional = false,
                      seenTrailing = seenTrailing,
                      seenRepeatedPos = seenRepeatedPos,
                      seenGroup = seenGroup,
                      seenPositional = seenPositional
                    )

  /** One field: select the instance (the field's single implicit search), dispatch on its
    * statically known shape for the shape x annotation checks and name folding, and recurse.
    */
  inline def step[E, Ft <: Tuple, At <: Tuple, Lt <: Tuple, Anns, L, Shorts, Longs](
      inline optional: Boolean,
      inline seenTrailing: Boolean,
      inline seenRepeatedPos: Boolean,
      inline seenGroup: Boolean,
      inline seenPositional: Boolean
  ): List[(Parser[?], Boolean)] =
    summonFrom:
      case p: Parser[E] =>
        inline p match
          case _: Parser.ValuedFlag[?] =>
            named[Ft, At, Lt, Anns, L, Shorts, Longs](
              p,
              optional,
              seenTrailing,
              seenRepeatedPos,
              seenGroup,
              seenPositional
            )
          case _: Parser.Flag[?] =>
            inline if optional then
              error("a flag Parser without a value parser cannot be used inside Option")
            else inline if hasAnn[flagged.positional, Anns] then
              error("a flag Parser without a value parser cannot be used positionally")
            else
              named[Ft, At, Lt, Anns, L, Shorts, Longs](
                p,
                optional,
                seenTrailing,
                seenRepeatedPos,
                seenGroup,
                seenPositional
              )
          case _: Parser.Value[?] =>
            named[Ft, At, Lt, Anns, L, Shorts, Longs](
              p,
              optional,
              seenTrailing,
              seenRepeatedPos,
              seenGroup,
              seenPositional
            )
          case _: Parser.Repeated[?] =>
            inline if optional then
              error(
                "Option of a repeated Parser is not supported: the plain type is empty when absent"
              )
            else inline if hasAnn[flagged.positional, Anns] then
              (p, optional) :: fieldsRec[Ft, At, Lt, Shorts, Longs](
                seenTrailing = seenTrailing,
                seenRepeatedPos = true,
                seenGroup = seenGroup,
                seenPositional = true
              )
            else
              named[Ft, At, Lt, Anns, L, Shorts, Longs](
                p,
                optional,
                seenTrailing,
                seenRepeatedPos,
                seenGroup,
                seenPositional
              )
          case _: Parser.Trailing[?] =>
            inline if seenTrailing then error("only one trailing field is supported per command")
            else inline if hasAnn[flagged.positional, Anns] then
              error("@positional cannot be combined with a trailing field")
            else inline if hasAnn[flagged.short, Anns] then
              error("@short cannot be combined with a trailing field")
            else inline if hasAnn[flagged.name, Anns] then
              error("@name has no effect on a trailing field")
            else
              (p, optional) :: fieldsRec[Ft, At, Lt, Shorts, Longs](
                seenTrailing = true,
                seenRepeatedPos = seenRepeatedPos,
                seenGroup = seenGroup,
                seenPositional = seenPositional
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
            else inline if seenGroup then
              error("only one subcommand field is supported per command")
            else inline if seenPositional then
              error(
                "mixing positional fields with a subcommand field is ambiguous and not supported"
              )
            else
              (p, optional) :: fieldsRec[Ft, At, Lt, Shorts, Longs](
                seenTrailing = seenTrailing,
                seenRepeatedPos = seenRepeatedPos,
                seenGroup = true,
                seenPositional = seenPositional
              )
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
            else
              (p, optional) :: fieldsRec[Ft, At, Lt, Shorts, Longs](
                seenTrailing = seenTrailing,
                seenRepeatedPos = seenRepeatedPos,
                seenGroup = seenGroup,
                seenPositional = seenPositional
              )
          case _ =>
            error(
              "the shape of this field's Parser is not statically known: give the given a shape type such as Parser.Value[X], or build it with the Parser constructors / derivation clauses"
            )
      case _ =>
        // fails with Parser's missing-instance guidance
        (summonInline[Parser[E]], optional) :: Nil

  /** A field that surely becomes a named option: fold its constant names (if not positional) into
    * the claimed-name unions, testing membership first.
    */
  inline def named[Ft <: Tuple, At <: Tuple, Lt <: Tuple, Anns, L, Shorts, Longs](
      p: Parser[?],
      inline optional: Boolean,
      inline seenTrailing: Boolean,
      inline seenRepeatedPos: Boolean,
      inline seenGroup: Boolean,
      inline seenPositional: Boolean
  ): List[(Parser[?], Boolean)] =
    inline if hasAnn[flagged.positional, Anns] then
      (p, optional) :: fieldsRec[Ft, At, Lt, Shorts, Longs](
        seenTrailing = seenTrailing,
        seenRepeatedPos = seenRepeatedPos,
        seenGroup = seenGroup,
        seenPositional = true
      )
    else
      checkNewShort[Anns, Shorts]
      checkNewLong[Anns, L, Longs]
      (p, optional) :: fieldsRec[Ft, At, Lt, Shorts | ShortIn[Anns], Longs | EffLong[Anns, L]](
        seenTrailing = seenTrailing,
        seenRepeatedPos = seenRepeatedPos,
        seenGroup = seenGroup,
        seenPositional = seenPositional
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
