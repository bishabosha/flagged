package flagged.internal

import scala.compiletime.*
import flagged.Parser
import flagged.meta.{Ann, MethodMirror, MethodsMirror}
import steps.result.Result

/** Inline layer over [[flagged.meta.MethodsMirror]]: a method is a product whose construction is
  * invocation, a nested `@run` object is a sum — everything else is the class-derivation pipeline
  * unchanged ([[Derive.fieldsOf]] with its compile-time checks, [[Annots]], [[Assemble]]).
  *
  * The mirror reflects every member marked by a [[flagged.meta.Reflectable]] annotation; this layer
  * selects only those marked `@run` exactly, so foreign markers are visible to the mirror but never
  * become commands.
  */
object DeriveMethods:

  /** The group of `T`'s `@run` members as a subcommand sum. */
  inline def group[T, G <: MethodsMirror[T]](o: T, g: G): Command =
    inline if runMethodCount[g.MirroredEntries] + runObjectCount[g.MirroredEntries] == 0 then
      error("no @run methods or nested @run objects found in " + constValue[g.MirroredLabel])
    else
      val es = entriesOf[T, g.MirroredEntries](o, g.entries, 0)
      Assemble.sum(
        es.map(_(0)),
        Annots.makeSum[Any](Annots.targetAnnotsOf[g.MirroredSelfAnnotations], es.map(_(1))),
        es.map(_(2)),
        Derive.versionOf[T, g.MirroredSelfAnnotations]
      )

  /** `T`'s `@run` members as one parser: a lone method is a whole command — its parameters are the
    * top-level options — anything else is a subcommand group. Backs [[flagged.Flagged.Entry]],
    * whose call sites stay unchanged when a second command is added.
    */
  inline def parser[T, G <: MethodsMirror[T], R](o: T, g: G): Parser[R] =
    inline if runMethodCount[g.MirroredEntries] == 1 && runObjectCount[g.MirroredEntries] == 0 then
      val (cmd, prog) = pickSingle[T, g.MirroredEntries](o, g.entries, 0)
      Parser.make[R](cmd, prog)
    else groupParser[T, G, R](o, g)

  private inline def groupParser[T, G <: MethodsMirror[T], R](o: T, g: G): Parser[R] =
    Parser.makeGroup[R](
      group[T, G](o, g),
      Assemble.progName(
        constValue[g.MirroredLabel],
        Annots.targetAnnotsOf[g.MirroredSelfAnnotations]
      )
    )

  /** The single `@run` method of `T` as a whole command: `(command, prog name)`. */
  inline def single[T, G <: MethodsMirror[T]](o: T, g: G): (Command, String) =
    inline if runMethodCount[g.MirroredEntries] == 1 && runObjectCount[g.MirroredEntries] == 0 then
      pickSingle[T, g.MirroredEntries](o, g.entries, 0)
    else inline if runMethodCount[g.MirroredEntries] == 0 &&
      runObjectCount[g.MirroredEntries] == 1
    then
      error("Parser.method requires a single @run method, not a nested object; use Parser.methods")
    else inline if runMethodCount[g.MirroredEntries] + runObjectCount[g.MirroredEntries] == 0 then
      error("no @run methods or nested @run objects found in " + constValue[g.MirroredLabel])
    else error("Parser.method requires exactly one @run method; use Parser.methods for several")

  /** Is `flagged.run` itself among the [[Ann]]-encoded annotations? Other [[Reflectable]] markers
    * do not count.
    */
  private transparent inline def isRun[Anns]: Boolean =
    inline erasedValue[Anns] match
      case _: EmptyTuple                    => false
      case _: (Ann[flagged.run, ?, ?] *: _) => true
      case _: (_ *: t)                      => isRun[t]

  private transparent inline def runMethodCount[Es <: Tuple]: Int =
    inline erasedValue[Es] match
      case _: EmptyTuple => 0
      case _: (h *: t)   =>
        inline erasedValue[h] match
          case m: MethodMirror[?] =>
            (inline if isRun[m.MirroredSelfAnnotations] then 1 else 0) + runMethodCount[t]
          case _ => runMethodCount[t]

  private transparent inline def runObjectCount[Es <: Tuple]: Int =
    inline erasedValue[Es] match
      case _: EmptyTuple => 0
      case _: (h *: t)   =>
        inline erasedValue[h] match
          case g: MethodsMirror[?] =>
            (inline if isRun[g.MirroredSelfAnnotations] then 1 else 0) + runObjectCount[t]
          case _ => runObjectCount[t]

  /** The lone `@run` method entry; callers guarantee exactly one exists. */
  private inline def pickSingle[T, Es <: Tuple](o: T, es: Tuple, i: Int): (Command, String) =
    inline erasedValue[Es] match
      case _: EmptyTuple => error("pickSingle: no @run method (guarded by runMethodCount)")
      case _: (h *: t)   =>
        inline erasedValue[h] match
          case m: MethodMirror[?] =>
            inline if isRun[m.MirroredSelfAnnotations] then
              singleOf[T, h & MethodMirror[T]](o, es.productElement(i).asInstanceOf)
            else pickSingle[T, t](o, es, i + 1)
          case _ => pickSingle[T, t](o, es, i + 1)

  private inline def singleOf[T, M <: MethodMirror[T]](o: T, m: M): (Command, String) =
    val anns = Annots.targetAnnotsOf[m.MirroredSelfAnnotations]
    (methodCmd[T, M](o, m, anns), anns.name.getOrElse(Assemble.kebab(constValue[m.MirroredLabel])))

  private inline def entriesOf[T, Es <: Tuple](
      o: T,
      es: Tuple,
      i: Int
  ): List[(String, TargetAnnots, SubEntry)] =
    inline erasedValue[Es] match
      case _: EmptyTuple => Nil
      case _: (h *: t)   =>
        entryOf[T, h](o, es.productElement(i).asInstanceOf[h]) ::: entriesOf[T, t](o, es, i + 1)

  private inline def entryOf[T, H](o: T, h: H): List[(String, TargetAnnots, SubEntry)] =
    inline erasedValue[H] match
      case m: MethodMirror[?] =>
        inline if isRun[m.MirroredSelfAnnotations] then
          List(methodEntry[T, H & MethodMirror[T]](o, h.asInstanceOf[H & MethodMirror[T]]))
        else Nil
      case g: MethodsMirror[?] =>
        inline if isRun[g.MirroredSelfAnnotations] then
          List(groupEntry[T, H & MethodsMirror[T]](o, h.asInstanceOf[H & MethodsMirror[T]]))
        else Nil

  private inline def methodEntry[T, M <: MethodMirror[T]](
      o: T,
      m: M
  ): (String, TargetAnnots, SubEntry) =
    val anns = Annots.targetAnnotsOf[m.MirroredSelfAnnotations]
    val cmd  = methodCmd[T, M](o, m, anns)
    val name = anns.name.getOrElse(Assemble.kebab(constValue[m.MirroredLabel]))
    (constValue[m.MirroredLabel], anns, SubEntry.Node(() => Parser.make[Any](cmd, name)))

  private inline def groupEntry[T, G <: MethodsMirror[T]](
      o: T,
      g: G
  ): (String, TargetAnnots, SubEntry) =
    val anns = Annots.targetAnnotsOf[g.MirroredSelfAnnotations]
    val cmd  = group[T, G](o, g)
    val name = anns.name.getOrElse(Assemble.kebab(constValue[g.MirroredLabel]))
    (constValue[g.MirroredLabel], anns, SubEntry.Node(() => Parser.makeGroup[Any](cmd, name)))

  private inline def methodCmd[T, M <: MethodMirror[T]](
      o: T,
      m: M,
      onMethod: TargetAnnots
  ): Command =
    Assemble.product(
      Derive.labelsOf[m.MirroredElemLabels],
      Derive.fieldsOf[m.MirroredElemTypes, m.MirroredAnnotations],
      m,
      onMethod,
      arr => Result.Ok(m.invoke(o, arr)),
      Derive.versionOf[T, m.MirroredSelfAnnotations]
    )
