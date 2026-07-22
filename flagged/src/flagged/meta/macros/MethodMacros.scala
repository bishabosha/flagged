package flagged.meta.macros

import scala.quoted.*
import flagged.meta.{MethodMirror, MethodsMirror}

/** The macro backing [[MethodsMirror]]: enumerate an object's methods (and nested objects) marked
  * by a [[flagged.meta.Reflectable]] annotation — `@run` in flagged — and mirror each one:
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

    private def alias(t: TypeRepr): TypeBounds = TypeBounds(t, t)

    private def isReflectable(s: Symbol): Boolean =
      s.annotations.exists(_.tpe.derivesFrom(reflectableSym))

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

    /** Mirror one method of the module `mod` (a static object value symbol). */
    private def methodMirror[T: Type](mod: Symbol, m: Symbol): (TypeRepr, TypeRepr, Expr[Any]) =
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
      val params                    = m.paramSymss.flatten.filter(_.isTerm)
      val paramTypes                = mt.map(_.paramTypes).getOrElse(Nil)
      val resType                   = mt.map(_.resType).getOrElse(Ref(mod).tpe.memberType(m))
      val labels: List[TypeRepr]    = params.map(p => ConstantType(StringConstant(p.name)))
      val paramAnns: List[TypeRepr] = params.map(slot)

      // default getters live on the same object: `m$default$N`
      val getters: List[(Int, Term)] = params.zipWithIndex.flatMap { (p, i) =>
        if p.flags.is(Flags.HasDefault) then
          mod.moduleClass
            .methodMember(s"${m.name}$$default$$${i + 1}")
            .headOption
            .map(g => i -> Ref(mod).select(g))
        else None
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

      val refined =
        List(
          "MirroredLabel"           -> ConstantType(StringConstant(m.name)),
          "MirroredElemLabels"      -> tupleType(labels),
          "MirroredElemTypes"       -> tupleType(paramTypes),
          "MirroredSelfAnnotations" -> slot(m),
          "MirroredAnnotations"     -> tupleType(paramAnns),
          "MirroredResult"          -> resType
        ).foldLeft(TypeRepr.of[MethodMirror[T]]) { case (acc, (name, tpe)) =>
          Refinement(acc, name, alias(tpe))
        }

      val instance = '{
        new MethodMirror[T]:
          def invoke(receiver: T, args: Array[Any]): Any = ${ invokeBody('args) }
          def defaultArgument(index: Int): Any           = ${ argBody('index) }
          def hasDefault(index: Int): Boolean            = ${ hasBody('index) }
      }
      (refined, resType, cast(instance, refined).asExpr)

    /** Mirror the module value symbol `mod` as a group: (refined type, result union, instance). */
    private def groupMirror[T: Type](mod: Symbol, label: String): (TypeRepr, TypeRepr, Expr[Any]) =
      val members = reflectableMembers(mod.moduleClass)
      if members.isEmpty then
        report.errorAndAbort(
          s"no methods or nested objects with a meta.Reflectable annotation (such as @run) " +
            s"found in ${mod.name}"
        )
      val mirrored = members.map { d =>
        if d.isDefDef then methodMirror[T](mod, d)
        else groupMirror[T](d, d.name)
      }
      val entryTypes  = mirrored.map(_(0))
      val result      = mirrored.map(_(1)).reduceLeft(OrType(_, _))
      val entriesExpr =
        Expr.ofTupleFromSeq(mirrored.map(_(2))).asExprOf[Tuple]

      val refined =
        List(
          "MirroredLabel"           -> ConstantType(StringConstant(label)),
          "MirroredSelfAnnotations" -> slot(mod),
          "MirroredEntries"         -> tupleType(entryTypes),
          "MirroredResult"          -> result
        ).foldLeft(TypeRepr.of[MethodsMirror.Of[T]]) { case (acc, (name, tpe)) =>
          Refinement(acc, name, alias(tpe))
        }
      val instance = '{
        new MethodsMirror.Of[T]:
          type MirroredEntries = Tuple
          val entries: Tuple = $entriesExpr
      }
      (refined, result, cast(instance, refined).asExpr)

    def mirror[T: Type]: Expr[MethodsMirror.Of[T]] =
      val mod = TypeRepr.of[T].termSymbol
      if mod == Symbol.noSymbol || !mod.flags.is(Flags.Module) then
        report.errorAndAbort(
          s"Parser.method/methods requires an object; ${TypeRepr.of[T].show} is not one " +
            "(pass the object itself, e.g. Parser.methods(app))"
        )
      val (refined, _, instance) = groupMirror[T](mod, mod.name)
      refined.asType match
        case '[t] =>
          '{ $instance.asInstanceOf[t & MethodsMirror.Of[T]] }.asExprOf[MethodsMirror.Of[T]]
