package claw.internal

import scala.quoted.*

/** Per-field default values of a case class, in declaration order.
  * `values` is empty for non-products.
  */
final class Defaults[A](val values: List[Option[() => Any]])

object Defaults:
  /** The one thing `Mirror` cannot see: default argument getters. */
  inline def of[A]: Defaults[A] = ${ MetaMacros.defaults[A] }

/** The claw annotations present on a type, its constructor fields, and its cases.
  * Lists are empty where not applicable.
  */
final class Annots[A](
    val onType: List[Any],
    val perField: List[List[Any]],
    val perCase: List[List[Any]]
)

object Annots:
  /** The other thing `Mirror` cannot see: annotations. */
  inline def of[A]: Annots[A] = ${ MetaMacros.annots[A] }

/** The two residual macros backing [[Defaults]] and [[Annots]]. Everything else in
  * claw's derivation is `Mirror` + `inline`.
  */
object MetaMacros:

  def defaults[A: Type](using Quotes): Expr[Defaults[A]] =
    import quotes.reflect.*
    val sym = TypeRepr.of[A].typeSymbol
    val entries: List[Expr[Option[() => Any]]] =
      if sym.isClassDef && sym.flags.is(Flags.Case) && !sym.flags.is(Flags.Module) then
        val comp = sym.companionModule
        val params = sym.primaryConstructor.paramSymss.flatten.filter(_.isTerm)
        sym.caseFields.indices.toList.map { i =>
          val hasDefault = params.lift(i).exists(_.flags.is(Flags.HasDefault))
          val getter =
            if hasDefault then
              comp
                .methodMember(s"apply$$default$$${i + 1}")
                .headOption
                .orElse(comp.methodMember(s"$$lessinit$$greater$$default$$${i + 1}").headOption)
            else None
          getter match
            case Some(dm) => '{ Some(() => ${ Ref(comp).select(dm).asExprOf[Any] }) }
            case None     => '{ None }
        }
      else Nil
    '{ new Defaults[A](${ Expr.ofList(entries) }) }

  def annots[A: Type](using Quotes): Expr[Annots[A]] =
    import quotes.reflect.*
    val sym = TypeRepr.of[A].typeSymbol

    def clawAnnots(s: Symbol): Expr[List[Any]] =
      val terms = s.annotations
        .filter(_.tpe.typeSymbol.fullName.startsWith("claw."))
        .map(_.asExprOf[Any])
      Expr.ofList(terms)

    val onType = clawAnnots(sym)

    val perField: Expr[List[List[Any]]] =
      if sym.isClassDef && sym.flags.is(Flags.Case) && !sym.flags.is(Flags.Module) then
        val params = sym.primaryConstructor.paramSymss.flatten.filter(_.isTerm)
        Expr.ofList(sym.caseFields.zipWithIndex.map { (f, i) =>
          val fromParam = params.lift(i).map(clawAnnots).getOrElse('{ Nil })
          '{ $fromParam ++ ${ clawAnnots(f) } }
        })
      else '{ Nil }

    val perCase: Expr[List[List[Any]]] =
      val children = sym.children
      if children.nonEmpty then Expr.ofList(children.map(clawAnnots))
      else '{ Nil }

    '{ new Annots[A]($onType, $perField, $perCase) }
