package flagged.meta.macros

import scala.collection.mutable.ListBuffer
import scala.quoted.*
import flagged.meta.AnnotMirror
import flagged.meta.ArgumentList

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
    * of compile-time constants — literals, constant-typed expressions (constant folds like
    * `"a" + "b"`, `final val` references), or tuples of such constants, e.g. an `aliases` list. A
    * type-parameterised annotation mirrors at its applied type, which `Mirror.ProductOf` supports;
    * curried annotation constructors (secondary argument lists) are rejected as not generic — their
    * shape cannot be rebuilt through `Mirror.ProductOf`. An occurrence of a case-class
    * `StaticAnnotation` that fails these restrictions warns (via [[reportDropped]]) and is left out
    * of the mirror; annotations outside the mirrorable universe (not a case class, not static) are
    * skipped silently by design.
    *
    * Shared with [[MethodMacros]], which mirrors methods with the same encodings.
    */
  private[macros] class AnnotHelper(using val q: Quotes):
    import q.reflect.*

    def tupleType(ts: List[TypeRepr]): TypeRepr =
      ts.foldRight(TypeRepr.of[EmptyTuple])((h, acc) => TypeRepr.of[*:].appliedTo(List(h, acc)))

    private def constArg(t: Term): Option[TypeRepr] = t match
      case NamedArg(_, v)  => constArg(v)
      case Literal(c)      => Some(ConstantType(c))
      case Typed(inner, _) => constArg(inner)
      case _               =>
        // not a literal tree, but possibly still a constant *type*: the typer constant-folds
        // pure operations on constants (`"usage: " + "..."`, `1 + 2`), and a reference to a
        // constant-typed `final val` keeps its constant through `widenTermRefByName` — both
        // survive TASTy unpickling, so cross-unit annotations fold identically
        t.tpe.widenTermRefByName.dealias match
          case c: ConstantType => Some(c)
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

    /** Diagnostics for annotation occurrences left out of the mirror, buffered during synthesis:
      * the reporter keeps only one diagnostic per position, and macro diagnostics all render at the
      * expansion site, so several `report.warning` calls from one expansion would surface only the
      * first. Entry points flush the buffer with [[reportDropped]].
      */
    private val droppedAnnots = ListBuffer.empty[(String, Position)]

    private def drop(msg: String, pos: Position): Unit = droppedAnnots += ((msg, pos))

    /** Emit the buffered drop diagnostics as a single warning, positioned at the first dropped
      * annotation — the anchor `@nowarn` suppression keys on.
      */
    def reportDropped(): Unit =
      droppedAnnots.toList match
        case Nil                   => ()
        case (msg, pos) :: Nil     => report.warning(msg, pos)
        case all @ ((_, pos) :: _) => report.warning(all.map("- " + _(0)).mkString("\n"), pos)
      droppedAnnots.clear()

    /** The annotated definition, described for diagnostics — the warning positions at the mirror
      * summon, so the message itself must say where the annotation sits: `parameter `x` of class
      * foo.Y`, `method foo.Y.run`, `enum case foo.Colour.Red`.
      */
    private def describe(s: Symbol): String =
      def kind(s: Symbol): String =
        if s.isClassDef then
          if s.flags.is(Flags.Module) then "object"
          else if s.flags.is(Flags.Enum) then if s.flags.is(Flags.Case) then "enum case" else "enum"
          else if s.flags.is(Flags.Trait) then "trait"
          else "class"
        else if s.isDefDef then "method"
        else if s.flags.is(Flags.Enum) then "enum case"
        else "value"
      def name(s: Symbol): String = s.fullName.stripSuffix("$").replace("$.", ".")
      if s.flags.is(Flags.Param) then
        // a constructor parameter reports its class, a method parameter its method
        val site = if s.maybeOwner.name == "<init>" then s.maybeOwner.maybeOwner else s.maybeOwner
        s"parameter `${s.name}` of ${kind(site)} ${name(site)}"
      else s"${kind(s)} ${name(s)}"

    /** Resolve the constructor arguments in declaration order: positional args by index, named args
      * by parameter name. Only *explicitly provided* arguments are encoded — each yields its
      * parameter name, constant type, and parameter index. An omitted argument (or a typer-inserted
      * default getter) contributes nothing, provided the parameter declares a default —
      * materialisation looks it up via the annotation's `Defaults` mirror. Returns the provided
      * triples, or `None` when the occurrence is not mirrorable — a non-constant argument buffers a
      * warning naming the annotated definition `owner`, so the occurrence is not dropped silently.
      * The warning carries the annotation's own position: the compiler renders macro diagnostics at
      * the expansion site, but `@nowarn` suppression keys on the position given here.
      */
    private def resolveArgs(
        owner: Symbol,
        annot: Term,
        sym: Symbol,
        args: List[Term]
    ): Option[List[(String, TypeRepr, Int)]] =
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
              case None      =>
                drop(
                  s"@${sym.name} on ${describe(owner)} is ignored: its `${p.name}` argument" +
                    " is not a compile-time constant (a literal, a constant-folded expression," +
                    " or a tuple of constants)",
                  annot.pos
                )
                ok = false
          case None => ok &= p.flags.is(Flags.HasDefault)
      }
      Option.when(ok)(provided.result())

    /** The single explicit argument list of an annotation constructor application: `new A(args)`,
      * or `new A[T](args)` — a type-parameterised annotation mirrors at its applied type, which
      * `Mirror.ProductOf` supports.
      */
    private object AnnotApply:
      def unapply(t: Term): Option[List[Term]] = t match
        case Apply(Select(New(_), _), args)               => Some(args)
        case Apply(TypeApply(Select(New(_), _), _), args) => Some(args)
        case _                                            => None

    /** `ArgumentList[a, names, values, indices]` for one annotation occurrence on `owner`, if it is
      * mirrorable.
      */
    private def annType(owner: Symbol, a: Term): Option[TypeRepr] =
      val tpe = a.tpe
      val sym = tpe.typeSymbol
      val ok  = tpe <:< TypeRepr.of[scala.annotation.StaticAnnotation] && sym.flags.is(Flags.Case)
      a match
        case AnnotApply(args) if ok =>
          resolveArgs(owner, a, sym, args).map { provided =>
            TypeRepr
              .of[ArgumentList]
              .appliedTo(
                List(
                  tpe,
                  tupleType(provided.map((n, _, _) => ConstantType(StringConstant(n)))),
                  tupleType(provided.map(_(1))),
                  tupleType(provided.map((_, _, i) => ConstantType(IntConstant(i))))
                )
              )
          }
        case _ if ok =>
          // a case-class StaticAnnotation was clearly meant to be mirrored; if its application
          // shape is not (a curried constructor), say so rather than dropping the occurrence
          // silently
          drop(
            s"@${sym.name} on ${describe(owner)} is ignored: annotations applied with several" +
              " argument lists cannot be rebuilt through Mirror.ProductOf",
            a.pos
          )
          None
        case _ => None

    def slot(s: Symbol): TypeRepr = tupleType(s.annotations.reverse.flatMap(annType(s, _)))

    // the refinements are built as quoted types, like [[MethodMacros]]: the members must be true
    // aliases — match-type capture through an alias pattern does not reduce over `>: t <: t`
    // bounds, and the Refinement API can only express bounds (a bare TypeRepr makes a val member
    // instead)

    def product[A: Type]: Expr[AnnotMirror.Product[A]] =
      val sym = TypeRepr.of[A].typeSymbol
      if !(sym.isClassDef && sym.flags.is(Flags.Case) && !sym.flags.is(Flags.Module)) then
        report.errorAndAbort(s"No product AnnotMirror for ${TypeRepr.of[A].show}: not a case class")
      val params   = sym.primaryConstructor.paramSymss.flatten.filter(_.isTerm)
      val selfSlot = slot(sym)
      // case fields are the primary constructor's parameter accessors — the same declarations —
      // so the constructor's parameter symbols are the single source of a field's annotations
      val perField = sym.caseFields.zipWithIndex.map { (_, i) =>
        params.lift(i).fold(TypeRepr.of[EmptyTuple])(slot)
      }
      reportDropped()
      (selfSlot.asType, tupleType(perField).asType).runtimeChecked match
        case ('[type msa <: Tuple; msa], '[type man <: Tuple; man]) =>
          '{
            AnnotMirror.Product.Empty.asInstanceOf[
              AnnotMirror.Product[A] {
                type MirroredSelfAnnotations = msa
                type MirroredAnnotations     = man
              }
            ]
          }

    def sum[A: Type]: Expr[AnnotMirror.Sum[A]] =
      val sym = TypeRepr.of[A].typeSymbol
      if sym.children.isEmpty then
        report.errorAndAbort(s"No sum AnnotMirror for ${TypeRepr.of[A].show}: no cases found")
      val selfSlot = slot(sym)
      val perCase  = tupleType(sym.children.map(slot))
      reportDropped()
      (selfSlot.asType, perCase.asType).runtimeChecked match
        case ('[type msa <: Tuple; msa], '[type man <: Tuple; man]) =>
          '{
            AnnotMirror.Sum.Empty.asInstanceOf[
              AnnotMirror.Sum[A] {
                type MirroredSelfAnnotations = msa
                type MirroredAnnotations     = man
              }
            ]
          }
