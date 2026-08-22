package flagged.internal

import compiletime.{summonFrom, erasedValue, constValue, constValueTuple}
import compiletime.ops.int./
import flagged.meta.{ArgumentList, AnnotMirror}
import scala.annotation.publicInBinary

/** Allocation-free optional character for derivation metadata. */
@publicInBinary private[flagged] object MaybeChar:
  final val empty: Int               = -1
  inline def apply(value: Char): Int = value.toInt

/** flagged's `@cmd` arguments on a type or an enum case, extracted at compile time from an
  * [[AnnotMirror]] — fully typed, no `Any` and no runtime type tests.
  */
private[flagged] final case class TargetAnnots @publicInBinary() (
    name: Option[String],
    help: Option[String],
    hidden: Boolean = false,
    aliases: IndexedSeq[String] = Vector.empty,
    default: Boolean = false
)

@publicInBinary private[flagged] object TargetAnnots:
  val empty: TargetAnnots = TargetAnnots(None, None, false)

/** flagged's `@opt` arguments on one constructor field, extracted at compile time. */
private[flagged] final case class FieldAnnots @publicInBinary() (
    name: Option[String],
    short: Int,
    help: Option[String],
    positional: Boolean,
    hidden: Boolean = false,
    group: Option[String] = None,
    aliases: IndexedSeq[String] = Vector.empty,
    split: Int = MaybeChar.empty,
    greedy: Boolean = false
)

@publicInBinary private[flagged] object FieldAnnots:
  val empty: FieldAnnots = FieldAnnots(None, MaybeChar.empty, None, false, false)

  /** The record of a field with no `@opt` in a positional-by-default derivation: positional, like a
    * `@scala.main` parameter.
    */
  val emptyPositional: FieldAnnots = FieldAnnots(None, MaybeChar.empty, None, true, false)

/** Runtime carrier for annotations extracted at compile time. Only sums need one: a product's
  * fields are walked in [[Derive]], which extracts each slot as it reaches the field
  * ([[Annots.fieldAnnotsOf]]) and hands it straight to the builder, whereas a sum's cases are
  * assembled together.
  */
@publicInBinary private[flagged] object Annots:

  /** Annotations of an enum / sealed trait: on the type itself and per case; `perCase` empty on an
    * inhabited sum means no case carries one — index through [[caseAnnots]].
    */
  final case class Sum[A](onType: TargetAnnots, perCase: IndexedSeq[TargetAnnots])

  extension (s: Annots.Sum[?])
    def caseAnnots(i: Int): TargetAnnots =
      if s.perCase.isEmpty then TargetAnnots.empty else s.perCase(i)

  def makeSum[A](onType: TargetAnnots, perCase: IndexedSeq[TargetAnnots]): Annots.Sum[A] =
    Annots.Sum(onType, perCase)

  /** Extract flagged's annotations for a sum into typed records. */
  inline def sumAnnots[A]: Annots.Sum[A] =
    summonFrom:
      case am: AnnotMirror.Sum[A] =>
        makeSum[A](
          targetAnnotsOf[am.MirroredSelfAnnotations],
          targetAnnotsEach[am.MirroredAnnotations]
        )

  inline def targetAnnotsOf[Anns]: TargetAnnots =
    inline erasedValue[Anns] match
      case _: EmptyTuple => TargetAnnots.empty
      case _             => targetAnnotsOfSome[Anns]

  // Extraction reads the slot's `@cmd` / `@opt` occurrence once: the sparse argument columns type
  // exactly the arguments written at the use site, so the fold walks the (names, values) tuples
  // in parallel, and an omitted argument costs nothing — absence is the record's default. Every
  // matched value materialises as a single constValue — no Mirror/Defaults machinery per query.

  inline def targetAnnotsOfSome[Anns]: TargetAnnots =
    inline erasedValue[Anns] match
      case _: EmptyTuple                                  => TargetAnnots.empty
      case _: (ArgumentList[flagged.cmd, ns, vs, ?] *: ?) =>
        inline erasedValue[ns] match
          // bare @cmd: nothing to collect — share the empty record
          case _: EmptyTuple => TargetAnnots.empty
          case _             =>
            collectTarget[ns, vs](
              None,
              None,
              false,
              Vector.empty,
              false
            )
      case _: (_ *: t) => targetAnnotsOfSome[t]

  // the folds thread each extracted value as an inline parameter and call the constructor once
  // at the end — no intermediate copies
  inline def collectTarget[Ns <: Tuple, Vs <: Tuple](
      inline name: Option[String],
      inline help: Option[String],
      inline hidden: Boolean,
      inline aliases: IndexedSeq[String],
      inline default: Boolean
  ): TargetAnnots =
    inline erasedValue[(Ns, Vs)] match
      case _: (EmptyTuple, ?)         => TargetAnnots(name, help, hidden, aliases, default)
      case _: ("name" *: nt, v *: vt) =>
        collectTarget[nt, vt](Some(constValue[v & String]), help, hidden, aliases, default)
      case _: ("help" *: nt, v *: vt) =>
        collectTarget[nt, vt](name, Some(constValue[v & String]), hidden, aliases, default)
      case _: ("hidden" *: nt, v *: vt) =>
        collectTarget[nt, vt](name, help, constValue[v & Boolean], aliases, default)
      case _: ("default" *: nt, v *: vt) =>
        collectTarget[nt, vt](name, help, hidden, aliases, constValue[v & Boolean])
      case _: ("aliases" *: nt, v *: vt) =>
        collectTarget[nt, vt](name, help, hidden, Derive.labelsOf[v & Tuple], default)
      case _: (? *: nt, ? *: vt) =>
        collectTarget[nt, vt](name, help, hidden, aliases, default)

  /** The field record: from the slot's `@opt` when there is one — its `positional` starts `false`,
    * so an annotated field is a named option unless it says `positional = true` — otherwise the
    * no-`@opt` record selected by `positionalDefault` (positional in a command, named in a
    * `Parser.Shared` group).
    */
  inline def fieldAnnotsOf[Anns](inline positionalDefault: Boolean): FieldAnnots =
    inline erasedValue[Anns] match
      case _: EmptyTuple => noOptAnnots(positionalDefault)
      case _             => fieldAnnotsOfSome[Anns](positionalDefault)

  inline def noOptAnnots(inline positionalDefault: Boolean): FieldAnnots =
    inline if positionalDefault then FieldAnnots.emptyPositional else FieldAnnots.empty

  inline def fieldAnnotsOfSome[Anns](inline positionalDefault: Boolean): FieldAnnots =
    inline erasedValue[Anns] match
      case _: EmptyTuple                                  => noOptAnnots(positionalDefault)
      case _: (ArgumentList[flagged.opt, ns, vs, ?] *: ?) =>
        inline erasedValue[ns] match
          // bare @opt — the common named-option marker: nothing to collect, so every such field
          // shares the empty record instead of calling the constructor
          case _: EmptyTuple => FieldAnnots.empty
          case _             =>
            collectField[ns, vs](
              None,
              MaybeChar.empty,
              None,
              false,
              false,
              None,
              Vector.empty,
              MaybeChar.empty,
              false
            )
      case _: (_ *: t) => fieldAnnotsOfSome[t](positionalDefault)

  // inline parameters: arguments substitute as expressions, so pass-through values bind
  // nothing per step and only the arguments actually written are ever constructed
  inline def collectField[Ns <: Tuple, Vs <: Tuple](
      inline name: Option[String],
      inline short: Int,
      inline help: Option[String],
      inline positional: Boolean,
      inline hidden: Boolean,
      inline group: Option[String],
      inline aliases: IndexedSeq[String],
      inline split: Int,
      inline greedy: Boolean
  ): FieldAnnots =
    inline erasedValue[(Ns, Vs)] match
      case _: (EmptyTuple, ?) =>
        FieldAnnots(name, short, help, positional, hidden, group, aliases, split, greedy)
      case _: ("name" *: nt, v *: vt) =>
        collectField[nt, vt](
          Some(constValue[v & String]),
          short,
          help,
          positional,
          hidden,
          group,
          aliases,
          split,
          greedy
        )
      case _: ("help" *: nt, v *: vt) =>
        collectField[nt, vt](
          name,
          short,
          Some(constValue[v & String]),
          positional,
          hidden,
          group,
          aliases,
          split,
          greedy
        )
      case _: ("short" *: nt, v *: vt) =>
        collectField[nt, vt](
          name,
          MaybeChar(constValue[v & Char]),
          help,
          positional,
          hidden,
          group,
          aliases,
          split,
          greedy
        )
      case _: ("aliases" *: nt, v *: vt) =>
        collectField[nt, vt](
          name,
          short,
          help,
          positional,
          hidden,
          group,
          Derive.labelsOf[v & Tuple],
          split,
          greedy
        )
      case _: ("positional" *: nt, v *: vt) =>
        collectField[nt, vt](
          name,
          short,
          help,
          constValue[v & Boolean],
          hidden,
          group,
          aliases,
          split,
          greedy
        )
      case _: ("hidden" *: nt, v *: vt) =>
        collectField[nt, vt](
          name,
          short,
          help,
          positional,
          constValue[v & Boolean],
          group,
          aliases,
          split,
          greedy
        )
      case _: ("group" *: nt, v *: vt) =>
        collectField[nt, vt](
          name,
          short,
          help,
          positional,
          hidden,
          Some(constValue[v & String]),
          aliases,
          split,
          greedy
        )
      case _: ("split" *: nt, v *: vt) =>
        collectField[nt, vt](
          name,
          short,
          help,
          positional,
          hidden,
          group,
          aliases,
          MaybeChar(constValue[v & Char]),
          greedy
        )
      case _: ("greedy" *: nt, v *: vt) =>
        collectField[nt, vt](
          name,
          short,
          help,
          positional,
          hidden,
          group,
          aliases,
          split,
          constValue[v & Boolean]
        )
      case _: (? *: nt, ? *: vt) =>
        collectField[nt, vt](name, short, help, positional, hidden, group, aliases, split, greedy)

  // the walk halves the slot tuple (inline depth O(log n), matching Derive.walk) — annotation
  // slots hold only literal constant types, which survive the destructuring binders

  type Half[T <: Tuple] = Tuple.Size[T] / 2

  /** Do none of the annotation slots hold anything? Then the walks share `Vector.empty` instead of
    * building an all-default vector. An inline match, not a match type: the slots arrive as an
    * abstract mirror path that only inline-match reduction resolves.
    */
  private transparent inline def allEmpty[Slots]: Boolean =
    inline erasedValue[Slots] match
      case _: EmptyTuple        => true
      case _: (EmptyTuple *: t) => allEmpty[t]
      case _: (_ *: _)          => false

  inline def targetAnnotsEach[Slots]: IndexedSeq[TargetAnnots] =
    inline if allEmpty[Slots] then Vector.empty
    else
      val b = Vector.newBuilder[TargetAnnots]
      targetAnnotsInto[Slots](b)
      b.result()

  inline def targetAnnotsInto[Slots](b: scala.collection.mutable.Growable[TargetAnnots]): Unit =
    inline erasedValue[Slots] match
      case _: EmptyTuple                   => ()
      case _: (a *: EmptyTuple)            => b += targetAnnotsOf[a]
      case _: (a *: b0 *: EmptyTuple)      => b += targetAnnotsOf[a] += targetAnnotsOf[b0]
      case _: (a *: b0 *: c *: EmptyTuple) =>
        b += targetAnnotsOf[a] += targetAnnotsOf[b0] += targetAnnotsOf[c]
      case _: (a *: b0 *: c *: d *: EmptyTuple) =>
        b += targetAnnotsOf[a] += targetAnnotsOf[b0] += targetAnnotsOf[c] += targetAnnotsOf[d]
      case _: (h *: t) =>
        targetAnnotsInto[Tuple.Take[h *: t, Half[h *: t]]](b)
        targetAnnotsInto[Tuple.Drop[h *: t, Half[h *: t]]](b)
