package flagged.meta.macros

import scala.quoted.*
import flagged.meta.{MethodMirror, MethodsMirror}

/** The macro backing [[MethodsMirror]]: enumerate an object's methods (and nested objects) marked
  * by a [[flagged.meta.Reflectable]] annotation — `@cmd` in flagged — and mirror each one:
  * structure as refined type members in the same encodings `Mirror`/`AnnotMirror` use, plus the
  * minimal term residue: an invoker and the default argument getters. Everything downstream is the
  * ordinary inline derivation pipeline.
  */
object MethodMacros:

  def methodsMirror[T: Type](using Quotes): Expr[MethodsMirror.Of[T]] =
    new MethodHelper().mirror[T]

  private class MethodHelper(using Quotes) extends AnnotationMacros.AnnotHelper:
    import q.reflect.*

    private val reflectableSym = TypeRepr.of[flagged.meta.Reflectable].typeSymbol

    private def isReflectable(s: Symbol): Boolean =
      s.annotations.exists(_.tpe.derivesFrom(reflectableSym))

    /** Whether reaching `s` needs an enclosing instance. The mirror's invoker and default-argument
      * getters select on the module symbol directly (`Ref(mod)`), a reference that carries no
      * prefix, and they are emitted inside an anonymous class — so a module that is a *member* of a
      * class or trait would erase to a `this` with no outer accessor (a compiler crash). A module
      * nested in packages or other objects needs no prefix, and a module local to a term is
      * captured lexically, since the mirror is summoned in that same scope.
      */
    private def needsOuter(s: Symbol): Boolean =
      val owner = s.maybeOwner
      if owner.isNoSymbol || owner.isPackageDef then false
      else if owner.isDefDef || owner.isValDef then false // local to a term: captured
      else if owner.isClassDef && !owner.flags.is(Flags.Module) then true
      else needsOuter(owner)

    private def cast(e: Expr[Any], tpe: TypeRepr): Term =
      tpe.asType match
        case '[pt] => '{ $e.asInstanceOf[pt] }.asTerm

    /** The reflectable members of `owner`'s module class, in declaration order: method symbols and
      * nested module (value) symbols carrying a [[flagged.meta.Reflectable]] annotation.
      */
    private def reflectableMembers(ownerClass: Symbol): List[Symbol] =
      ownerClass.declarations.filter { d =>
        isReflectable(d) &&
        ((d.isDefDef && !d.isClassConstructor) || (d.isTerm && d.flags.is(Flags.Module)))
      }

    /** Mirror one method of the module `mod` (a static object value symbol): its refined
      * [[MethodMirror]] type and the instance, cast to it.
      */
    private def methodMirror[T: Type](mod: Symbol, m: Symbol): (TypeRepr, Expr[Any]) =
      val mt = Ref(mod).tpe.memberType(m) match
        case mt: MethodType =>
          mt.resType match
            case _: MethodType =>
              report.errorAndAbort(
                s"command method ${m.name}: multiple parameter lists are not supported"
              )
            case _ => Some(mt)
        case _: PolyType =>
          report.errorAndAbort(s"command method ${m.name}: type parameters are not supported")
        case _ => None // parameterless `def m`
      val params                 = m.paramSymss.flatten.filter(_.isTerm)
      val paramTypes             = mt.map(_.paramTypes).getOrElse(Nil)
      val resType                = mt.map(_.resType).getOrElse(Ref(mod).tpe.memberType(m))
      val labels: List[TypeRepr] = params.map(p => ConstantType(StringConstant(p.name)))
      // the mirror is an AnnotMirror for the method: one shared computation of its two members
      val (selfAnns, paramAnns) = annotEncoding(m, params)

      // default getters live on the same object: `m$default$N`
      val getters: List[(Int, Term)] = params.zipWithIndex.flatMap { (p, i) =>
        Option.when(p.flags.is(Flags.HasDefault)):
          mod.moduleClass.methodMember(s"${m.name}$$default$$${i + 1}").headOption match
            case Some(g) => i -> Ref(mod).select(g)
            // silently dropping it would turn an optional parameter into a required one
            case None =>
              report.errorAndAbort(
                s"command method ${m.name}: no default-argument getter for parameter '${p.name}'"
              )
      }

      def invokeBody(args: Expr[Array[Any]]): Expr[Any] =
        val sel  = Ref(mod).select(m)
        val call =
          if mt.isEmpty then sel
          else
            Apply(
              sel,
              paramTypes.zipWithIndex.map((pt, i) => cast('{ $args(${ Expr(i) }) }, pt))
            )
        call.asExprOf[Any]

      def argBody(idx: Expr[Int]): Expr[Any] =
        val fallback = CaseDef(
          Wildcard(),
          None,
          '{ throw new NoSuchElementException("no default argument at index " + $idx) }.asTerm
        )
        val cases = getters.map((i, g) => CaseDef(Literal(IntConstant(i)), None, g))
        Match(idx.asTerm, cases :+ fallback).asExprOf[Any]

      def hasBody(idx: Expr[Int]): Expr[Boolean] =
        getters.map(_(0)) match
          case Nil     => '{ false }
          case indices =>
            val pattern = indices.map(i => Literal(IntConstant(i))) match
              case single :: Nil => single
              case many          => Alternatives(many)
            Match(
              idx.asTerm,
              List(
                CaseDef(pattern, None, '{ true }.asTerm),
                CaseDef(Wildcard(), None, '{ false }.asTerm)
              )
            ).asExprOf[Boolean]

      // built as a quoted type: the members must be true aliases — match-type capture through an
      // alias pattern (MethodMirror.ResultOf) does not reduce over `>: t <: t` bounds, and the
      // Refinement API can only express bounds (a bare TypeRepr makes a val member instead)
      val refined =
        (
          ConstantType(StringConstant(m.name)).asType,
          tupleType(labels).asType,
          tupleType(paramTypes).asType,
          selfAnns.asType,
          paramAnns.asType,
          resType.asType
        ).runtimeChecked match
          case (
                '[type ml <: String; ml],
                '[type ell <: Tuple; ell],
                '[type elt <: Tuple; elt],
                '[type msa <: Tuple; msa],
                '[type man <: Tuple; man],
                '[r]
              ) =>
            TypeRepr.of[
              MethodMirror[T] {
                type MirroredLabel           = ml
                type MirroredElemLabels      = ell
                type MirroredElemTypes       = elt
                type MirroredSelfAnnotations = msa
                type MirroredAnnotations     = man
                type MirroredResult          = r
              }
            ]

      val instance = '{
        new MethodMirror[T]:
          def invoke(args: Array[Any]): Any    = ${ invokeBody('args) }
          def defaultArgument(index: Int): Any = ${ argBody('index) }
          def hasDefault(index: Int): Boolean  = ${ hasBody('index) }
      }
      (refined, cast(instance, refined).asExpr)

    private def tagged(tag: TypeRepr, args: TypeRepr*): TypeRepr =
      tag match
        case AppliedType(tycon, _) => tycon.appliedTo(args.toList)
        case other                 => report.errorAndAbort(s"not a tag constructor: ${other.show}")

    /** Mirror the module value symbol `mod`, one level deep: methods are mirrored in place and a
      * nested object contributes only an `Entry.Scope` tag of its type — callers summon a fresh
      * `MethodsMirror` to descend.
      */
    private def groupMirror[T: Type](mod: Symbol, label: String): (TypeRepr, Expr[Any]) =
      val members = reflectableMembers(mod.moduleClass)
      if members.isEmpty then
        report.errorAndAbort(
          s"no methods or nested objects with a meta.Reflectable annotation (such as @cmd) " +
            s"found in ${mod.name}"
        )

      val methodTag = TypeRepr.of[MethodsMirror.Entry.Method[Unit]]
      val scopeTag  = TypeRepr.of[MethodsMirror.Entry.Scope[Unit]]

      // per member: the Entry tag type and, for methods, the mirror instance at that index
      val entries: List[(TypeRepr, Option[Expr[Any]])] = members.map { d =>
        if d.isDefDef then
          val (refined, instance) = methodMirror[T](mod, d)
          (tagged(methodTag, refined), Some(instance))
        else (tagged(scopeTag, Ref(d).tpe), None)
      }

      def methodBody(idx: Expr[Int]): Expr[MethodMirror[T]] =
        val fallback = CaseDef(
          Wildcard(),
          None,
          '{
            throw new NoSuchElementException(
              "no method mirror at index " + $idx + " (a Scope entry: summon its own mirror)"
            )
          }.asTerm
        )
        val cases = entries.zipWithIndex.collect { case ((_, Some(instance)), i) =>
          CaseDef(Literal(IntConstant(i)), None, cast(instance, TypeRepr.of[MethodMirror[T]]))
        }
        Match(idx.asTerm, cases :+ fallback).asExprOf[MethodMirror[T]]

      val refined =
        (
          ConstantType(StringConstant(label)).asType,
          slot(mod).asType,
          tupleType(entries.map(_(0))).asType
        ).runtimeChecked match
          case ('[type ml <: String; ml], '[type msa <: Tuple; msa], '[type es <: Tuple; es]) =>
            TypeRepr.of[
              MethodsMirror.Of[T] {
                type MirroredLabel           = ml
                type MirroredSelfAnnotations = msa
                type MirroredEntries         = es
              }
            ]
      val instance = '{
        new MethodsMirror.Of[T]:
          def method(index: Int): MethodMirror[T] = ${ methodBody('index) }
      }
      (refined, cast(instance, refined).asExpr)

    def mirror[T: Type]: Expr[MethodsMirror.Of[T]] =
      val mod = TypeRepr.of[T].termSymbol
      if mod == Symbol.noSymbol || !mod.flags.is(Flags.Module) then
        report.errorAndAbort(
          s"Parser.method/methods requires an object; ${TypeRepr.of[T].show} is not one " +
            "(pass the object itself, e.g. Parser.methods(app))"
        )
      if needsOuter(mod) then
        report.errorAndAbort(
          s"Parser.method/methods requires an object reachable without an enclosing instance; " +
            s"${mod.name} is a member of ${mod.owner.name} " +
            "(commands are invoked without a receiver, so a per-instance object has none)"
        )
      val (refined, instance) = groupMirror[T](mod, mod.name)
      refined.asType match
        case '[t] =>
          '{ $instance.asInstanceOf[t & MethodsMirror.Of[T]] }.asExprOf[MethodsMirror.Of[T]]
