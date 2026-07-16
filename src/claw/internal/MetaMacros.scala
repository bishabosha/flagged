package claw.internal

import scala.quoted.*

/** Per-field default values of a case class, in declaration order.
  * `values` is empty for non-products.
  */
final class Defaults[A](val values: List[Option[() => Any]])

object Defaults:
  /** The one thing `Mirror` cannot see: default argument getters. */
  inline def of[A]: Defaults[A] = ${ MetaMacros.defaults[A] }

/** Runtime carrier for materialised annotations, built from an [[AnnotMirror]] by
  * `Derive.productAnnots` / `Derive.sumAnnots`. Shaped like the type they describe:
  * products carry per-field slots, sums per-case slots.
  */
enum Annots[A]:
  /** Annotations of a case class: on the type itself and per constructor field. */
  case Product[T](onType: List[Any], perField: List[List[Any]]) extends Annots[T]

  /** Annotations of an enum / sealed trait: on the type itself and per case. */
  case Sum[T](onType: List[Any], perCase: List[List[Any]]) extends Annots[T]

  def onType: List[Any]

/** The two residual macros backing [[Defaults]] and [[AnnotMirror]]. Everything else
  * in claw's derivation is `Mirror` + `inline`.
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

  def annotMirrorProduct[A: Type](using Quotes): Expr[AnnotMirror.Product[A]] =
    new AnnotHelper().product[A]

  def annotMirrorSum[A: Type](using Quotes): Expr[AnnotMirror.Sum[A]] =
    new AnnotHelper().sum[A]

  /** Synthesizes refined `AnnotMirror` types. Only *types* are computed — no
    * annotation values are constructed. Mirrored: case-class `StaticAnnotation`s
    * applied with exactly one argument list of literal constants. Curried annotation
    * constructors (secondary argument lists) are rejected as not generic — their
    * shape cannot be rebuilt through `Mirror.ProductOf`.
    */
  private class AnnotHelper(using val q: Quotes):
    import q.reflect.*

    private def tupleType(ts: List[TypeRepr]): TypeRepr =
      ts.foldRight(TypeRepr.of[EmptyTuple])((h, acc) => TypeRepr.of[*:].appliedTo(List(h, acc)))

    private def constArg(t: Term): Option[TypeRepr] = t match
      case NamedArg(_, v) => constArg(v)
      case Literal(c)     => Some(ConstantType(c))
      case _              => None

    /** `Ann[a, args]` for one annotation occurrence, if it is mirrorable. */
    private def annType(a: Term): Option[TypeRepr] =
      val tpe = a.tpe
      val ok = tpe <:< TypeRepr.of[scala.annotation.StaticAnnotation] &&
        tpe.typeSymbol.flags.is(Flags.Case)
      a match
        case Apply(Select(New(_), _), args) if ok =>
          args
            .foldRight(Option(List.empty[TypeRepr])) { (arg, acc) =>
              for tail <- acc; c <- constArg(arg) yield c :: tail
            }
            .map(argTypes => TypeRepr.of[Ann].appliedTo(List(tpe, tupleType(argTypes))))
        case _ => None

    private def slot(s: Symbol): TypeRepr = tupleType(s.annotations.reverse.flatMap(annType))

    private def slotOf(annotTerms: List[Term]): TypeRepr = tupleType(annotTerms.flatMap(annType))

    private def alias(t: TypeRepr): TypeBounds = TypeBounds(t, t)

    def product[A: Type]: Expr[AnnotMirror.Product[A]] =
      val sym = TypeRepr.of[A].typeSymbol
      if !(sym.isClassDef && sym.flags.is(Flags.Case) && !sym.flags.is(Flags.Module)) then
        report.errorAndAbort(s"No product AnnotMirror for ${TypeRepr.of[A].show}: not a case class")
      val params = sym.primaryConstructor.paramSymss.flatten.filter(_.isTerm)
      val perField = sym.caseFields.zipWithIndex.map { (f, i) =>
        val merged = params.lift(i).map(_.annotations.reverse).getOrElse(Nil) ++ f.annotations.reverse
        slotOf(merged)
      }
      val refined =
        Refinement(
          Refinement(TypeRepr.of[AnnotMirror.Product[A]], "MirroredAnnotations", alias(slot(sym))),
          "MirroredFieldAnnotations",
          alias(tupleType(perField))
        )
      refined.asType match
        case '[t] =>
          '{ (new AnnotMirror.Product[A] {}).asInstanceOf[t & AnnotMirror.Product[A]] }

    def sum[A: Type]: Expr[AnnotMirror.Sum[A]] =
      val sym = TypeRepr.of[A].typeSymbol
      if sym.children.isEmpty then
        report.errorAndAbort(s"No sum AnnotMirror for ${TypeRepr.of[A].show}: no cases found")
      val refined =
        Refinement(
          Refinement(TypeRepr.of[AnnotMirror.Sum[A]], "MirroredAnnotations", alias(slot(sym))),
          "MirroredCaseAnnotations",
          alias(tupleType(sym.children.map(slot)))
        )
      refined.asType match
        case '[t] =>
          '{ (new AnnotMirror.Sum[A] {}).asInstanceOf[t & AnnotMirror.Sum[A]] }
