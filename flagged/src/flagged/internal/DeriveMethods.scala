package flagged.internal

import language.experimental.captureChecking
import language.experimental.separationChecking

import scala.compiletime.*
import scala.compiletime.ops.int.+
import scala.annotation.publicInBinary
import flagged.Parser
import flagged.meta.{MethodMirror, MethodsMirror}
import flagged.runner.MethodEntry
import flagged.runner.MethodEntry.{EntriesResults, HasCmd}
import steps.result.Result

/** Inline layer over [[MethodEntry]]: a method is a product whose construction is invocation, a
  * nested `@cmd` object is a sum — everything else is the class-derivation pipeline unchanged
  * ([[Derive.fieldsOf]] with its compile-time checks, [[Annots]], [[Assemble]]).
  *
  * Every entry point takes the single [[MethodEntry]] bundle: the walks run over its
  * [[MethodEntry.EntriesResults]] tower, and descending into a scope reuses the nested tower stored
  * in the node rather than repeating any implicit search. Only members marked `@cmd` exactly become
  * commands; other [[flagged.meta.Reflectable]] markers are visible to the mirror but skipped here.
  *
  * Questions about the tower — is this member a command, how many are there, which slots do they
  * carry — are match types rather than inline-match recursion, like [[Derive.HasAnnT]]: each folds
  * once in the (cached) type domain instead of re-expanding per entry at every call site that asks.
  * Only the walks that build terms ([[pickSingle]], [[entriesInto]]) stay inline.
  */
@publicInBinary private[flagged] object DeriveMethods:

  /** The group of `T`'s `@cmd` members as a subcommand sum. */
  inline def group[T, R <: MethodEntry[T]](r: R): Command =
    inline if commandCount[r.Entries] == 0 then
      error("no @cmd methods or nested @cmd objects found in " + constValue[r.mirror.MirroredLabel])
    else
      checkSumRules[r.Entries]
      val eb = Vector.newBuilder[(String, TargetAnnots, Command)]
      entriesInto[T, r.Entries](r.mirror, r.entries, 0, eb)
      val es = eb.result()
      Assemble.sum(
        es.map(_(0)),
        Annots.makeSum[Any](Annots.targetAnnotsOf[r.mirror.MirroredSelfAnnotations], es.map(_(1))),
        es.map(e => SubEntry.Cmd(e(2))),
        Derive.versionOf[T, r.mirror.MirroredSelfAnnotations]
      )

  /** `T`'s `@cmd` members as one parser: a lone method is a whole command — its parameters are the
    * top-level options — anything else is a subcommand group. Backs [[flagged.Flagged.Entry]],
    * whose call sites stay unchanged when a second command is added.
    */
  inline def parser[T, R <: MethodEntry[T], Out](r: R): Parser[Out] =
    inline if isLoneMethod[r.Entries] then
      val (cmd, prog) = pickSingle[T, r.Entries](r.mirror, 0)
      Parser.make[Out](cmd, prog)
    else
      Parser.makeGroup[Out](
        group[T, R](r),
        Assemble.progName(
          constValue[r.mirror.MirroredLabel],
          Annots.targetAnnotsOf[r.mirror.MirroredSelfAnnotations]
        )
      )

  /** The single `@cmd` method of `T` as a whole command: `(command, prog name)`. */
  inline def single[T, R <: MethodEntry[T]](r: R): (Command, String) =
    inline if isLoneMethod[r.Entries] then pickSingle[T, r.Entries](r.mirror, 0)
    else inline if commandCount[r.Entries] == 0 then
      error("no @cmd methods or nested @cmd objects found in " + constValue[r.mirror.MirroredLabel])
    else inline if constValue[CmdMethods[r.Entries]] == 0 then
      error("Parser.method requires a single @cmd method, not a nested object; use Parser.methods")
    else error("Parser.method requires exactly one @cmd method; use Parser.methods for several")

  // ---- tower queries ----------------------------------------------------------
  // One match type per question, each a fold over the entry tower. Scrutinees are the node types
  // themselves — never an unreduced computation — so reductions cache and the arithmetic folds on
  // literals (the cheap regime measured in `bench.RuleCostProbe`).

  /** Is the method behind an [[EntriesResults.MethodNode]] marked `@cmd`? */
  private type MethodIsCmd[M] = HasCmd[MethodMirror.AnnotsOf[M]]

  /** Is the object whose tower an [[EntriesResults.ScopeNode]] holds marked `@cmd`? */
  private type ScopeIsCmd[R] = HasCmd[MethodEntry.SelfAnnotsOf[R]]

  private type OneIf[B <: Boolean] <: Int = B match
    case true  => 1
    case false => 0

  private type CmdMethods[ER] <: Int = ER match
    case EntriesResults.Empty                     => 0
    case EntriesResults.MethodNode[?, m, ?, re]   => OneIf[MethodIsCmd[m]] + CmdMethods[re]
    case EntriesResults.ScopeNode[?, ?, ?, re, ?] => CmdMethods[re]

  private type CmdScopes[ER] <: Int = ER match
    case EntriesResults.Empty                      => 0
    case EntriesResults.MethodNode[?, ?, ?, re]    => CmdScopes[re]
    case EntriesResults.ScopeNode[?, ?, sr, re, ?] => OneIf[ScopeIsCmd[sr]] + CmdScopes[re]

  // The counts are folded once each, in the type domain; the trivial comparisons on them stay in
  // the term domain, where two literals fold directly — a match type would have to reduce the
  // whole fold again as an (unmemoized) scrutinee.

  /** How many of `T`'s members are commands at all. */
  private inline def commandCount[ER]: Int =
    constValue[CmdMethods[ER]] + constValue[CmdScopes[ER]]

  /** Whether the whole group is one `@cmd` method — then it *is* the command, parsed flat. */
  private inline def isLoneMethod[ER]: Boolean =
    constValue[CmdMethods[ER]] == 1 && constValue[CmdScopes[ER]] == 0

  // ---- sum-level rules --------------------------------------------------------
  // The sum-level rules of [[Derive.checkSumRules]], restated over the entry tower: the enum path
  // reads its per-case annotation slots from a `Mirror.SumOf`, which a `@cmd` object has no
  // counterpart for. Only members that are commands are considered, so annotations on a non-`@cmd`
  // member are as invisible here as the member itself.

  /** The annotation slots of the group's commands, in declaration order — the tuple the enum path
    * gets from `AnnotMirror.Sum#MirroredAnnotations`.
    */
  private type CommandSlots[ER] <: Tuple = ER match
    case EntriesResults.Empty                   => EmptyTuple
    case EntriesResults.MethodNode[?, m, ?, re] =>
      ConsIfCmd[MethodMirror.AnnotsOf[m], CommandSlots[re]]
    case EntriesResults.ScopeNode[?, ?, sr, re, ?] =>
      ConsIfCmd[MethodEntry.SelfAnnotsOf[sr], CommandSlots[re]]

  private type ConsIfCmd[Anns <: Tuple, T <: Tuple] <: Tuple = HasCmd[Anns] match
    case true  => Anns *: T
    case false => T

  /** At most one `@cmd(default = true)` command, and no duplicate constant command names — the same
    * two rules [[Derive.checkSumRules]] enforces on an enum, over the same shape of slot tuple.
    */
  private inline def checkSumRules[ER]: Unit = Derive.checkSumRules[CommandSlots[ER]]

  /** `@cmd(default = true)` names the command to run when no command token is given; a lone `@cmd`
    * method is the whole command, with no token to omit and no siblings to choose between.
    */
  private inline def checkNoDefault[Anns]: Unit =
    inline if constValue[Derive.IsDefaultCmd[Anns]] then
      error("@cmd(default = true) has no effect on a single @cmd method (it is the only command)")
    else ()

  // ---- walks ------------------------------------------------------------------

  /** The lone `@cmd` method entry; callers guarantee exactly one exists. */
  private inline def pickSingle[T, ER](g: MethodsMirror[T], i: Int): (Command, String) =
    inline erasedValue[ER] match
      case _: EntriesResults.Empty =>
        error("pickSingle: no @cmd method (guarded by isLoneMethod)")
      case _: EntriesResults.MethodNode[?, m, ?, re] =>
        inline if constValue[MethodIsCmd[m]] then
          singleOf[T, m & MethodMirror[T]](g.method(i).asInstanceOf[m & MethodMirror[T]])
        else pickSingle[T, re](g, i + 1)
      case _: EntriesResults.ScopeNode[?, ?, ?, re, ?] => pickSingle[T, re](g, i + 1)

  private inline def singleOf[T, M <: MethodMirror[T]](m: M): (Command, String) =
    checkNoDefault[m.MirroredSelfAnnotations]
    val anns = Annots.targetAnnotsOf[m.MirroredSelfAnnotations]
    (methodCmd[T, M](m, anns), anns.name.getOrElse(Assemble.kebab(constValue[m.MirroredLabel])))

  /** One `(label, annotations, command)` per `@cmd` member, in declaration order — the three
    * parallel columns [[Assemble.sum]] consumes.
    */
  private inline def entriesInto[T, ER](
      g: MethodsMirror[T],
      er: ER,
      i: Int,
      b: scala.collection.mutable.Growable[(String, TargetAnnots, Command)]
  ): Unit =
    inline erasedValue[ER] match
      case _: EntriesResults.Empty                   => ()
      case _: EntriesResults.MethodNode[s, m, t, re] =>
        val node = er.asInstanceOf[
          EntriesResults.MethodNode[s, m & MethodMirror[s], t & Tuple, EntriesResults[t & Tuple]]
        ]
        inline if constValue[MethodIsCmd[m]] then
          b += methodEntry[T, m & MethodMirror[T]](g.method(i).asInstanceOf[m & MethodMirror[T]])
        entriesInto[T, re](g, node.rest.asInstanceOf[re], i + 1, b)
      case _: EntriesResults.ScopeNode[s, t, sr, re, oo] =>
        val node = er.asInstanceOf[
          EntriesResults.ScopeNode[
            s,
            t & Tuple,
            sr & MethodEntry[s],
            EntriesResults[t & Tuple],
            oo
          ]
        ]
        scopeEntryInto[s, sr & MethodEntry[s]](node.results, b)
        entriesInto[T, re](g, node.rest.asInstanceOf[re], i + 1, b)

  /** Descend into a nested command object: its whole tower was summoned once, during
    * [[MethodEntry]] derivation, and rides in the node.
    */
  private inline def scopeEntryInto[S, SR <: MethodEntry[S]](
      sr: SR,
      b: scala.collection.mutable.Growable[(String, TargetAnnots, Command)]
  ): Unit =
    inline if constValue[ScopeIsCmd[SR]] then
      b += ((
        constValue[sr.mirror.MirroredLabel],
        Annots.targetAnnotsOf[sr.mirror.MirroredSelfAnnotations],
        group[S, SR](sr)
      ))

  private inline def methodEntry[T, M <: MethodMirror[T]](m: M): (String, TargetAnnots, Command) =
    val anns = Annots.targetAnnotsOf[m.MirroredSelfAnnotations]
    (constValue[m.MirroredLabel], anns, methodCmd[T, M](m, anns))

  private inline def methodCmd[T, M <: MethodMirror[T]](m: M, onMethod: TargetAnnots): Command =
    Derive
      .fieldsOf[m.MirroredElemLabels, m.MirroredElemTypes, m.MirroredAnnotations](m)
      .resultInto(
        onMethod,
        // the base is only ever non-zero for a spliced child, and a method command is never one
        // (`Parser.Shared` has its own derivation), so the storage array is passed through as-is:
        // `invoke` reads args(0) until the parameter count, and any spliced children's slots — plus
        // the root frame's result slot — sit past that.
        (arr, _, outIndex) =>
          Result.task:
            arr(outIndex) = m.invoke(arr)
        ,
        Derive.versionOf[T, m.MirroredSelfAnnotations]
      )
