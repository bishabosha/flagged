package flagged.uncheck

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.core.Contexts.{ctx, Context}
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Symbols.{requiredPackage, Symbol}
import dotty.tools.dotc.core.Types.{AnnotatedType, NamedType, Type, TypeTraverser}
import dotty.tools.dotc.plugins.{PluginPhase, StandardPlugin}
import dotty.tools.dotc.report
import dotty.tools.dotc.util.SrcPos

/** Semantic guard for the unchecked (published) variant of flagged: fails compilation on any
  * reference to the experimental capture-checking surface, wherever it comes from — an import, a
  * spelled or inferred type, an annotation, an `update`-flagged method, or the language feature
  * itself. The textual `Uncheck` rewrite in the build is best-effort; this phase makes the "no
  * experimental API in the published artifact" invariant sound at the symbol level.
  */
class UncheckPlugin extends StandardPlugin:
  override val name: String        = "flagged-uncheck"
  override val description: String =
    "rejects experimental capture-checking API in unchecked sources"

  override def initialize(options: List[String])(using Context): List[PluginPhase] =
    List(new UncheckPhase)

class UncheckPhase extends PluginPhase:
  import tpd.*

  override val phaseName: String       = "flaggedUncheck"
  override val runsAfter: Set[String]  = Set("typer")
  override val runsBefore: Set[String] = Set("pickler")

  private val experimentalFeatures =
    Set("captureChecking", "separationChecking", "pureFunctions")

  override def prepareForUnit(tree: Tree)(using Context): Context =
    val enabled =
      ctx.settings.language.value
        .map(_.toString)
        .filter(l => experimentalFeatures(l.split('.').last))
    if enabled.nonEmpty then
      reject(s"language feature(s) ${enabled.mkString(", ")} enabled via -language", tree.srcPos)
    checker.traverse(tree)
    ctx

  private def capsPackage(using Context): Symbol = requiredPackage("scala.caps").moduleClass

  private def fromCaps(sym: Symbol)(using Context): Boolean =
    sym.exists && sym.ownersIterator.contains(capsPackage)

  private def isRetains(sym: Symbol)(using Context): Boolean =
    sym.exists && sym.showFullName.startsWith("scala.annotation.retains")

  private def reject(what: String, pos: SrcPos)(using Context): Unit =
    report.error(
      s"experimental capture-checking API must not reach the unchecked variant: $what",
      pos
    )

  private def checkType(tp: Type, pos: SrcPos)(using Context): Unit =
    object tt extends TypeTraverser:
      def traverse(t: Type): Unit =
        t match
          case t: NamedType if fromCaps(t.symbol) =>
            reject(t.symbol.showFullName, pos)
          case AnnotatedType(parent, annot) if fromCaps(annot.symbol) || isRetains(annot.symbol) =>
            reject(s"annotation ${annot.symbol.showFullName}", pos)
            traverse(parent)
          case _ =>
            traverseChildren(t)
    tt.traverse(tp)

  private object checker extends TreeTraverser:
    override def traverse(tree: Tree)(using Context): Unit =
      tree match
        case imp: Import =>
          if fromCaps(imp.expr.symbol) || fromCaps(imp.expr.tpe.typeSymbol) then
            reject(s"import from scala.caps", imp.srcPos)
          if imp.expr.show.endsWith("language.experimental") then
            for sel <- imp.selectors if experimentalFeatures(sel.name.toString) do
              reject(s"import language.experimental.${sel.name}", imp.srcPos)
        case ref: RefTree if fromCaps(ref.symbol) =>
          reject(ref.symbol.showFullName, ref.srcPos)
        case md: MemberDef =>
          val sym = md.symbol
          // Mutable on a non-accessor method is the capture checker's `update` modifier; var
          // setters are Mutable too but always carry Accessor
          if sym.is(Flags.Method) && sym.is(Flags.Mutable) && !sym.is(Flags.Accessor) then
            reject(s"update method ${sym.name}", md.srcPos)
          for ann <- sym.annotations if fromCaps(ann.symbol) || isRetains(ann.symbol) do
            reject(s"annotation ${ann.symbol.showFullName}", md.srcPos)
          checkType(sym.info, md.srcPos)
        case tpt: TypeTree =>
          checkType(tpt.tpe, tpt.srcPos)
        case _ => ()
      traverseChildren(tree)
