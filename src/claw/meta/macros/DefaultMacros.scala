package claw.meta.macros

import scala.quoted.*
import claw.meta.Defaults

object DefaultMacros:
  def defaults[A: Type](using Quotes): Expr[Defaults[A]] =
    import quotes.reflect.*
    val sym = TypeRepr.of[A].typeSymbol

    /** (parameter index, default-getter call) for every parameter with a default. */
    val getters: List[(Int, Term)] =
      if sym.isClassDef && sym.flags.is(Flags.Case) && !sym.flags.is(Flags.Module) then
        val comp   = sym.companionModule
        val params = sym.primaryConstructor.paramSymss.flatten.filter(_.isTerm)
        params.zipWithIndex.flatMap { (p, i) =>
          if p.flags.is(Flags.HasDefault) then
            comp
              .methodMember(s"apply$$default$$${i + 1}")
              .headOption
              .orElse(comp.methodMember(s"$$lessinit$$greater$$default$$${i + 1}").headOption)
              .map(dm => i -> Ref(comp).select(dm))
          else None
        }
      else Nil

    def argBody(idx: Expr[Int]): Expr[Any] =
      val cases = getters.map { (i, getter) =>
        CaseDef(Literal(IntConstant(i)), None, getter)
      }
      val fallback = CaseDef(
        Wildcard(),
        None,
        '{ throw new NoSuchElementException("no default argument at index " + $idx) }.asTerm
      )
      Match(idx.asTerm, cases :+ fallback).asExprOf[Any]

    def hasBody(idx: Expr[Int]): Expr[Boolean] =
      getters.map(_._1) match
        case Nil     => '{ false }
        case indices =>
          val pattern = indices.map(i => Literal(IntConstant(i))) match
            case single :: Nil => single
            case many          => Alternatives(many)
          val cases = List(
            CaseDef(pattern, None, '{ true }.asTerm),
            CaseDef(Wildcard(), None, '{ false }.asTerm)
          )
          Match(idx.asTerm, cases).asExprOf[Boolean]

    '{
      new Defaults[A]:
        def defaultArgument(index: Int): Any = ${ argBody('index) }
        def hasDefault(index: Int): Boolean  = ${ hasBody('index) }
    }
