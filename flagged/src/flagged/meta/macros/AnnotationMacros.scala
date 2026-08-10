package flagged.meta.macros

import scala.quoted.*
import flagged.meta.AnnotMirror
import flagged.meta.Ann

/** The two residual macros backing [[Defaults]] and [[AnnotMirror]]. Everything else in flagged's
  * derivation is `Mirror` + `inline`.
  */
object AnnotationMacros:

  def annotMirrorProduct[A: Type](using Quotes): Expr[AnnotMirror.Product[A]] =
    new AnnotHelper().product[A]

  def annotMirrorSum[A: Type](using Quotes): Expr[AnnotMirror.Sum[A]] =
    new AnnotHelper().sum[A]

  /** Synthesizes refined `AnnotMirror` types. Only *types* are computed — no annotation values are
    * constructed. Mirrored: case-class `StaticAnnotation`s applied with exactly one argument list
    * of literal constants (or tuples of literal constants, e.g. an `aliases` list). Curried
    * annotation constructors (secondary argument lists) are rejected as not generic — their shape
    * cannot be rebuilt through `Mirror.ProductOf`.
    *
    * Shared with [[MethodMacros]], which mirrors methods with the same encodings.
    */
  private[macros] class AnnotHelper(using val q: Quotes):
    import q.reflect.*

    def tupleType(ts: List[TypeRepr]): TypeRepr =
      ts.foldRight(TypeRepr.of[EmptyTuple])((h, acc) => TypeRepr.of[*:].appliedTo(List(h, acc)))

    private lazy val namedTupleTycon: TypeRepr =
      TypeRepr.of[NamedTuple.NamedTuple[EmptyTuple, EmptyTuple]] match
        case AppliedType(tycon, _) => tycon
        case other                 => report.errorAndAbort(s"not a named tuple: ${other.show}")

    private def namedTupleType(names: List[TypeRepr], values: List[TypeRepr]): TypeRepr =
      namedTupleTycon.appliedTo(List(tupleType(names), tupleType(values)))

    private def constArg(t: Term): Option[TypeRepr] = t match
      case NamedArg(_, v)  => constArg(v)
      case Literal(c)      => Some(ConstantType(c))
      case Typed(inner, _) => constArg(inner)
      case _               => constTupleArg(t)

    /** A tuple-of-constants argument (`"out" *: EmptyTuple`, `("a", "b")`): its precise tuple of
      * constant types, reconstructed from the tree — the *typed* tree, since tuple element types
      * are widened by inference.
      */
    private def constTupleArg(t: Term): Option[TypeRepr] = t match
      case _ if t.tpe <:< TypeRepr.of[EmptyTuple]              => Some(TypeRepr.of[EmptyTuple])
      case Apply(TypeApply(Select(prefix, "*:"), _), h :: Nil) =>
        // `h *: t` is right-associated sugar for `t.*:(h)`: the receiver is the tail
        for
          head <- constArg(h)
          tail <- constTupleArg(prefix)
        yield TypeRepr.of[*:].appliedTo(List(head, tail))
      case Apply(TypeApply(fun, _), args)
          if fun.symbol.name == "apply" && t.tpe <:< TypeRepr.of[Tuple] =>
        // TupleN.apply — a literal tuple such as `("a", "b")`
        val elems = args.map(constArg)
        Option.when(elems.forall(_.isDefined))(tupleType(elems.flatten))
      case _ => None

    /** Recognize a typer-inserted default-getter reference. */
    private def isDefaultGetterRef(t: Term): Boolean =
      val name = t match
        case Select(_, n) => Some(n)
        case Ident(n)     => Some(n)
        case _            => None
      name.exists(_.contains("$default$"))

    /** Resolve the constructor arguments in declaration order: positional args by index, named args
      * by parameter name. Only *explicitly provided* arguments are encoded — each yields its
      * parameter name, constant type, and parameter index. An omitted argument (or a typer-inserted
      * default getter) contributes nothing, provided the parameter declares a default —
      * materialisation looks it up via the annotation's `Defaults` mirror. Returns the provided
      * triples and the constructor's arity, or `None` when the occurrence is not mirrorable.
      */
    private def resolveArgs(
        sym: Symbol,
        args: List[Term]
    ): Option[(List[(String, TypeRepr, Int)], Int)] =
      val params = sym.primaryConstructor.paramSymss.flatten.filter(_.isTerm)
      val named  = args.collect { case NamedArg(n, v) => n -> v }.toMap
      // note: isInstanceOf[NamedArg] would erase to the reflect type's bound (Term)
      // and match everything; classify with the extractor instead
      val positional = args.filter {
        case NamedArg(_, _) => false
        case _              => true
      }
      var ok       = true
      val provided = List.newBuilder[(String, TypeRepr, Int)]
      params.zipWithIndex.foreach { (p, i) =>
        named.get(p.name).orElse(positional.lift(i)) match
          case Some(t) if isDefaultGetterRef(t) => ok &= p.flags.is(Flags.HasDefault)
          case Some(t)                          =>
            constArg(t) match
              case Some(tpe) => provided += ((p.name, tpe, i))
              case None      => ok = false
          case None => ok &= p.flags.is(Flags.HasDefault)
      }
      Option.when(ok)((provided.result(), params.length))

    /** `Ann[a, args, arity, indices]` for one annotation occurrence, if it is mirrorable. */
    private def annType(a: Term): Option[TypeRepr] =
      val tpe = a.tpe
      val sym = tpe.typeSymbol
      val ok  = tpe <:< TypeRepr.of[scala.annotation.StaticAnnotation] && sym.flags.is(Flags.Case)
      a match
        case Apply(Select(New(_), _), args) if ok =>
          resolveArgs(sym, args).map { (provided, arity) =>
            TypeRepr
              .of[Ann]
              .appliedTo(
                List(
                  tpe,
                  namedTupleType(
                    provided.map((n, _, _) => ConstantType(StringConstant(n))),
                    provided.map(_(1))
                  ),
                  ConstantType(IntConstant(arity)),
                  tupleType(provided.map((_, _, i) => ConstantType(IntConstant(i))))
                )
              )
          }
        case _ => None

    def slot(s: Symbol): TypeRepr = tupleType(s.annotations.reverse.flatMap(annType))

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
      val params   = sym.primaryConstructor.paramSymss.flatten.filter(_.isTerm)
      val perField = sym.caseFields.zipWithIndex.map { (f, i) =>
        val merged =
          params.lift(i).map(_.annotations.reverse).getOrElse(Nil) ++ f.annotations.reverse
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
