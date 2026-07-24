package flagged.internal

import scala.compiletime.*
import flagged.Parser
import flagged.meta.{Ann, MethodMirror, MethodsMirror}
import flagged.internal.MethodResults.EntriesResults
import steps.result.Result

/** Inline layer over [[MethodResults]]: a method is a product whose construction is invocation, a
  * nested `@run` object is a sum — everything else is the class-derivation pipeline unchanged
  * ([[Derive.fieldsOf]] with its compile-time checks, [[Annots]], [[Assemble]]).
  *
  * Every entry point takes the single [[MethodResults]] bundle: the walks run over its
  * [[MethodResults.EntriesResults]] tower, and descending into a scope reuses the nested tower
  * stored in the node rather than repeating any implicit search. Only members marked `@run` exactly
  * become commands; other [[flagged.meta.Reflectable]] markers are visible to the mirror but
  * skipped here.
  */
object DeriveMethods:

  /** The group of `T`'s `@run` members as a subcommand sum. */
  inline def group[T, R <: MethodResults[T]](o: T, r: R): Command =
    inline if runMethodCount[r.Entries] + runObjectCount[r.Entries] == 0 then
      error("no @run methods or nested @run objects found in " + constValue[r.mirror.MirroredLabel])
    else
      val eb = Vector.newBuilder[(String, TargetAnnots, SubEntry)]
      entriesInto[T, r.Entries](o, r.mirror, r.entries, 0, eb)
      val es = eb.result()
      Assemble.sum(
        es.map(_(0)),
        Annots.makeSum[Any](Annots.targetAnnotsOf[r.mirror.MirroredSelfAnnotations], es.map(_(1))),
        es.map(_(2)),
        Derive.versionOf[T, r.mirror.MirroredSelfAnnotations]
      )

  /** `T`'s `@run` members as one parser: a lone method is a whole command — its parameters are the
    * top-level options — anything else is a subcommand group. Backs [[flagged.Flagged.Entry]],
    * whose call sites stay unchanged when a second command is added.
    */
  inline def parser[T, R <: MethodResults[T], Out](o: T, r: R): Parser[Out] =
    inline if runMethodCount[r.Entries] == 1 && runObjectCount[r.Entries] == 0 then
      val (cmd, prog) = pickSingle[T, r.Entries](o, r.mirror, 0)
      Parser.make[Out](cmd, prog)
    else
      Parser.makeGroup[Out](
        group[T, R](o, r),
        Assemble.progName(
          constValue[r.mirror.MirroredLabel],
          Annots.targetAnnotsOf[r.mirror.MirroredSelfAnnotations]
        )
      )

  /** The single `@run` method of `T` as a whole command: `(command, prog name)`. */
  inline def single[T, R <: MethodResults[T]](o: T, r: R): (Command, String) =
    inline if runMethodCount[r.Entries] == 1 && runObjectCount[r.Entries] == 0 then
      pickSingle[T, r.Entries](o, r.mirror, 0)
    else inline if runMethodCount[r.Entries] == 0 && runObjectCount[r.Entries] == 1 then
      error("Parser.method requires a single @run method, not a nested object; use Parser.methods")
    else inline if runMethodCount[r.Entries] + runObjectCount[r.Entries] == 0 then
      error("no @run methods or nested @run objects found in " + constValue[r.mirror.MirroredLabel])
    else error("Parser.method requires exactly one @run method; use Parser.methods for several")

  /** Is `flagged.run` itself among the [[Ann]]-encoded annotations? Other [[Reflectable]] markers
    * do not count.
    */
  private transparent inline def isRun[Anns]: Boolean =
    inline erasedValue[Anns] match
      case _: EmptyTuple                    => false
      case _: (Ann[flagged.run, ?, ?] *: _) => true
      case _: (_ *: t)                      => isRun[t]

  /** Is the method behind an [[EntriesResults.MethodNode]] marked `@run`? */
  private transparent inline def methodIsRun[M]: Boolean =
    inline erasedValue[M] match
      case m: MethodMirror[?] => isRun[m.MirroredSelfAnnotations]

  /** Is the tower stored in an [[EntriesResults.ScopeNode]] for an `@run` object? */
  private transparent inline def resultsAreRun[SR]: Boolean =
    inline erasedValue[SR] match
      case sr: MethodResults[?] => mirrorIsRun[sr.Mirror]

  private transparent inline def mirrorIsRun[SM]: Boolean =
    inline erasedValue[SM] match
      case sm: MethodsMirror[?] => isRun[sm.MirroredSelfAnnotations]

  private transparent inline def runMethodCount[ER]: Int =
    inline erasedValue[ER] match
      case _: EntriesResults.Empty                   => 0
      case _: EntriesResults.MethodNode[?, m, ?, re] =>
        (inline if methodIsRun[m] then 1 else 0) + runMethodCount[re]
      case _: EntriesResults.ScopeNode[?, ?, ?, re, ?] => runMethodCount[re]

  private transparent inline def runObjectCount[ER]: Int =
    inline erasedValue[ER] match
      case _: EntriesResults.Empty                      => 0
      case _: EntriesResults.MethodNode[?, ?, ?, re]    => runObjectCount[re]
      case _: EntriesResults.ScopeNode[?, ?, sr, re, ?] =>
        (inline if resultsAreRun[sr] then 1 else 0) + runObjectCount[re]

  /** The lone `@run` method entry; callers guarantee exactly one exists. */
  private inline def pickSingle[T, ER](o: T, g: MethodsMirror[T], i: Int): (Command, String) =
    inline erasedValue[ER] match
      case _: EntriesResults.Empty =>
        error("pickSingle: no @run method (guarded by runMethodCount)")
      case _: EntriesResults.MethodNode[?, m, ?, re] =>
        inline if methodIsRun[m] then
          singleOf[T, m & MethodMirror[T]](o, g.method(i).asInstanceOf[m & MethodMirror[T]])
        else pickSingle[T, re](o, g, i + 1)
      case _: EntriesResults.ScopeNode[?, ?, ?, re, ?] => pickSingle[T, re](o, g, i + 1)

  private inline def singleOf[T, M <: MethodMirror[T]](o: T, m: M): (Command, String) =
    val anns = Annots.targetAnnotsOf[m.MirroredSelfAnnotations]
    (methodCmd[T, M](o, m, anns), anns.name.getOrElse(Assemble.kebab(constValue[m.MirroredLabel])))

  private inline def entriesInto[T, ER](
      o: T,
      g: MethodsMirror[T],
      er: ER,
      i: Int,
      b: scala.collection.mutable.Growable[(String, TargetAnnots, SubEntry)]
  ): Unit =
    inline erasedValue[ER] match
      case _: EntriesResults.Empty                   => ()
      case _: EntriesResults.MethodNode[s, m, t, re] =>
        val node = er.asInstanceOf[
          EntriesResults.MethodNode[s, m & MethodMirror[s], t & Tuple, EntriesResults[t & Tuple]]
        ]
        inline if methodIsRun[m] then
          b += methodEntry[T, m & MethodMirror[T]](o, g.method(i).asInstanceOf[m & MethodMirror[T]])
        entriesInto[T, re](o, g, node.rest.asInstanceOf[re], i + 1, b)
      case _: EntriesResults.ScopeNode[s, t, sr, re, oo] =>
        val node = er.asInstanceOf[
          EntriesResults.ScopeNode[
            s,
            t & Tuple,
            sr & MethodResults[s],
            EntriesResults[t & Tuple],
            oo
          ]
        ]
        scopeEntryInto[s, sr & MethodResults[s]](node.results, b)
        entriesInto[T, re](o, g, node.rest.asInstanceOf[re], i + 1, b)

  /** Descend into a nested command object: its whole tower was summoned once, during
    * [[MethodResults]] derivation, and rides in the node — only the object instance itself is
    * recovered here, via `ValueOf`.
    */
  private inline def scopeEntryInto[S, SR <: MethodResults[S]](
      sr: SR,
      b: scala.collection.mutable.Growable[(String, TargetAnnots, SubEntry)]
  ): Unit =
    inline if isRun[sr.mirror.MirroredSelfAnnotations] then
      val anns = Annots.targetAnnotsOf[sr.mirror.MirroredSelfAnnotations]
      val cmd  = group[S, SR](summonInline[ValueOf[S]].value, sr)
      val name = anns.name.getOrElse(Assemble.kebab(constValue[sr.mirror.MirroredLabel]))
      b += ((
        constValue[sr.mirror.MirroredLabel],
        anns,
        SubEntry.Node(() => Parser.makeGroup[Any](cmd, name))
      ))

  private inline def methodEntry[T, M <: MethodMirror[T]](
      o: T,
      m: M
  ): (String, TargetAnnots, SubEntry) =
    val anns = Annots.targetAnnotsOf[m.MirroredSelfAnnotations]
    val cmd  = methodCmd[T, M](o, m, anns)
    val name = anns.name.getOrElse(Assemble.kebab(constValue[m.MirroredLabel]))
    (constValue[m.MirroredLabel], anns, SubEntry.Node(() => Parser.make[Any](cmd, name)))

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
