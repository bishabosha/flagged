package claw.internal

import scala.quoted.*

/** Per-field default values of a case class, in declaration order.
  * `values` is empty for non-products.
  */
final class Defaults[A](val values: List[Option[() => Any]])

object Defaults:
  /** The one thing `Mirror` cannot see: default argument getters. */
  inline def of[A]: Defaults[A] = ${ MetaMacros.defaults[A] }

/** claw's annotations on a type or an enum case, extracted at compile time from an
  * [[AnnotMirror]] — fully typed, no `Any` and no runtime type tests.
  */
final case class TargetAnnots(name: Option[claw.name], help: Option[claw.help])

object TargetAnnots:
  val empty: TargetAnnots = TargetAnnots(None, None)

/** claw's annotations on one constructor field, extracted at compile time. */
final case class FieldAnnots(
    name: Option[claw.name],
    short: Option[claw.short],
    help: Option[claw.help],
    positional: Boolean
)

object FieldAnnots:
  val empty: FieldAnnots = FieldAnnots(None, None, None, false)

/** Runtime carrier for extracted annotations, built by `Derive.productAnnots` /
  * `Derive.sumAnnots`. Shaped like the type they describe: products carry per-field
  * slots, sums per-case slots.
  */
enum Annots[A]:
  /** Annotations of a case class: on the type itself and per constructor field. */
  case Product[T](onType: TargetAnnots, perField: List[FieldAnnots]) extends Annots[T]

  /** Annotations of an enum / sealed trait: on the type itself and per case. */
  case Sum[T](onType: TargetAnnots, perCase: List[TargetAnnots]) extends Annots[T]

  def onType: TargetAnnots

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
      case NamedArg(_, v)  => constArg(v)
      case Literal(c)      => Some(ConstantType(c))
      case Typed(inner, _) => constArg(inner)
      case _               => None

    /** Recognize a typer-inserted default-getter reference. */
    private def isDefaultGetterRef(t: Term): Boolean =
      val name = t match
        case Select(_, n) => Some(n)
        case Ident(n)     => Some(n)
        case _            => None
      name.exists(_.contains("$default$"))

    /** Resolve the constructor arguments in declaration order: positional args by
      * index, named args by parameter name. A provided argument yields its constant
      * type paired with `false`; an omitted argument (or a typer-inserted default
      * getter) yields the parameter's *index* paired with `true` — the constant is
      * never read from trees; materialisation looks it up via the annotation's
      * `Defaults` mirror.
      */
    private def resolveArgs(sym: Symbol, args: List[Term]): Option[(List[TypeRepr], List[TypeRepr])] =
      val params = sym.primaryConstructor.paramSymss.flatten.filter(_.isTerm)
      val named = args.collect { case NamedArg(n, v) => n -> v }.toMap
      // note: isInstanceOf[NamedArg] would erase to the reflect type's bound (Term)
      // and match everything; classify with the extractor instead
      val positional = args.filter {
        case NamedArg(_, _) => false
        case _              => true
      }
      def defaulted(p: Symbol, i: Int): Option[(TypeRepr, Boolean)] =
        Option.when(p.flags.is(Flags.HasDefault))((ConstantType(IntConstant(i)), true))
      val resolved = params.zipWithIndex.map { (p, i) =>
        named.get(p.name).orElse(positional.lift(i)) match
          case Some(t) if isDefaultGetterRef(t) => defaulted(p, i)
          case Some(t)                          => constArg(t).map((_, false))
          case None                             => defaulted(p, i)
      }
      if resolved.forall(_.isDefined) then
        val pairs = resolved.flatten
        Some((pairs.map(_._1), pairs.map(p => ConstantType(BooleanConstant(p._2)))))
      else None

    /** `Ann[a, args, defaulted]` for one annotation occurrence, if it is mirrorable. */
    private def annType(a: Term): Option[TypeRepr] =
      val tpe = a.tpe
      val sym = tpe.typeSymbol
      val ok = tpe <:< TypeRepr.of[scala.annotation.StaticAnnotation] && sym.flags.is(Flags.Case)
      a match
        case Apply(Select(New(_), _), args) if ok =>
          resolveArgs(sym, args).map { (argTypes, defaultedFlags) =>
            TypeRepr.of[Ann].appliedTo(List(tpe, tupleType(argTypes), tupleType(defaultedFlags)))
          }
        case _ => None

    private def slot(s: Symbol): TypeRepr = tupleType(s.annotations.reverse.flatMap(annType))

    private def slotOf(annotTerms: List[Term]): TypeRepr = tupleType(annotTerms.flatMap(annType))

    private def alias(t: TypeRepr): TypeBounds = TypeBounds(t, t)

    /** Refine an `AnnotMirror` kind with the self and per-member annotation slots. */
    private def refine(base: TypeRepr, self: TypeRepr, members: List[TypeRepr]): TypeRepr =
      Refinement(
        Refinement(base, "MirroredSelfAnnotations", alias(self)),
        "MirroredAnnotations",
        alias(tupleType(members))
      )

    def product[A: Type]: Expr[AnnotMirror.Product[A]] =
      val sym = TypeRepr.of[A].typeSymbol
      if !(sym.isClassDef && sym.flags.is(Flags.Case) && !sym.flags.is(Flags.Module)) then
        report.errorAndAbort(s"No product AnnotMirror for ${TypeRepr.of[A].show}: not a case class")
      val params = sym.primaryConstructor.paramSymss.flatten.filter(_.isTerm)
      val perField = sym.caseFields.zipWithIndex.map { (f, i) =>
        val merged = params.lift(i).map(_.annotations.reverse).getOrElse(Nil) ++ f.annotations.reverse
        slotOf(merged)
      }
      refine(TypeRepr.of[AnnotMirror.Product[A]], slot(sym), perField).asType match
        case '[t] =>
          '{ (new AnnotMirror.Product[A] {}).asInstanceOf[t & AnnotMirror.Product[A]] }

    def sum[A: Type]: Expr[AnnotMirror.Sum[A]] =
      val sym = TypeRepr.of[A].typeSymbol
      if sym.children.isEmpty then
        report.errorAndAbort(s"No sum AnnotMirror for ${TypeRepr.of[A].show}: no cases found")
      refine(TypeRepr.of[AnnotMirror.Sum[A]], slot(sym), sym.children.map(slot)).asType match
        case '[t] =>
          '{ (new AnnotMirror.Sum[A] {}).asInstanceOf[t & AnnotMirror.Sum[A]] }
