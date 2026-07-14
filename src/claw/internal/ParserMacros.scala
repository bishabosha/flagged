package claw.internal

import scala.quoted.*
import claw.{Parser, Reader}

/** Compile-time derivation of `Parser` and `Reader` instances. */
object ParserMacros:

  def deriveParser[A: Type](using Quotes): Expr[Parser[A]] =
    new Helper().deriveParser[A]

  def deriveReader[A: Type](using Quotes): Expr[Reader[A]] =
    new Helper().deriveReader[A]

  private class Helper(using val q: Quotes):
    import q.reflect.*

    // ---- entry points -------------------------------------------------------

    def deriveParser[A: Type]: Expr[Parser[A]] =
      val tpe = TypeRepr.of[A].dealias
      val sym = tpe.typeSymbol
      val cmd = commandFor(tpe)
      val prog = strAnnot[claw.name](sym).getOrElse(kebab(sym.name.stripSuffix("$")))
      '{ Parser.make[A]($cmd, ${ Expr(prog) }) }

    def deriveReader[A: Type]: Expr[Reader[A]] =
      val tpe = TypeRepr.of[A].dealias
      val sym = tpe.typeSymbol
      if !isSumSym(sym) || !allSingleton(sym) then
        report.errorAndAbort(
          s"Reader can only be derived for enums (or sealed traits) whose cases are all parameterless, " +
            s"but ${sym.name} has parameterized cases. Derive claw.Parser instead for subcommands."
        )
      val pairs = Expr.ofList(sym.children.map { ch =>
        val nm = strAnnot[claw.name](ch).getOrElse(kebab(ch.name))
        val ref = singletonValue(ch).getOrElse(
          report.errorAndAbort(s"Case ${ch.name} of ${sym.name} is not a singleton")
        )
        '{ (${ Expr(nm) }, ${ ref.asExprOf[A] }) }
      })
      '{ Runtime.enumReader[A](${ Expr(enumMetavar(sym)) }, $pairs.toVector) }

    // ---- command construction ----------------------------------------------

    private def commandFor(tpe: TypeRepr): Expr[Command] =
      val sym = tpe.typeSymbol
      if isSumSym(sym) then
        val cases = subCasesExpr(tpe)
        val desc = strAnnot[claw.help](sym).getOrElse("")
        '{
          Command(
            ${ Expr(desc) },
            Vector.empty,
            Vector.empty,
            Some(SubGroup(0, false, None, $cases)),
            arr => arr(0),
            1
          )
        }
      else if sym.flags.is(Flags.Case) && !sym.flags.is(Flags.Module) then productCommand(tpe)
      else
        report.errorAndAbort(
          s"Cannot derive a CLI parser for ${tpe.show}: expected a case class, an enum, or a sealed trait"
        )

    private def subCasesExpr(tpe: TypeRepr): Expr[Vector[SubCase]] =
      val sym = tpe.typeSymbol
      val children = sym.children
      if children.isEmpty then
        report.errorAndAbort(s"${sym.name} has no cases to derive subcommands from")
      val caseExprs = children.map { ch =>
        val nm = strAnnot[claw.name](ch).getOrElse(kebab(ch.name))
        val hp = strAnnot[claw.help](ch).getOrElse("")
        val cmdE = singletonValue(ch) match
          case Some(ref) => '{ Command.leaf(${ ref.asExprOf[Any] }, ${ Expr(hp) }) }
          case None      => commandFor(ch.typeRef)
        '{ SubCase(${ Expr(nm) }, ${ Expr(hp) }, $cmdE) }
      }
      '{ ${ Expr.ofList(caseExprs) }.toVector }

    private def productCommand(tpe: TypeRepr): Expr[Command] =
      val sym = tpe.typeSymbol
      val fields = sym.caseFields
      val ctor = sym.primaryConstructor
      if ctor.paramSymss.exists(_.exists(_.isType)) then
        report.errorAndAbort(s"Cannot derive a CLI parser for generic type ${tpe.show}")
      if ctor.paramSymss.count(_.forall(_.isTerm)) > 1 then
        report.errorAndAbort(
          s"Cannot derive a CLI parser for ${sym.name}: multiple parameter lists are not supported"
        )
      val params = ctor.paramSymss.flatten.filter(_.isTerm)
      val comp = sym.companionModule
      val desc = strAnnot[claw.help](sym).getOrElse("")

      val optExprs = List.newBuilder[Expr[OptSpec]]
      val posExprs = List.newBuilder[Expr[PosSpec]]
      // (spec kind for validation) — required, optionalOrDefaulted, repeated
      val posKinds = List.newBuilder[(String, String)]
      var subGroup: Option[Expr[SubGroup]] = None
      val longNames = collection.mutable.Set.empty[String]
      val shortNames = collection.mutable.Set.empty[Char]

      fields.zipWithIndex.foreach { (f, i) =>
        val p = params(i)
        val ft = tpe.memberType(f).widen.dealias
        val longName = strAnnot[claw.name](p, f).getOrElse(kebab(f.name))
        val helpText = strAnnot[claw.help](p, f).getOrElse("")
        val shortName = charAnnot[claw.short](p, f)
        val isPositional = hasAnnot[claw.positional](p, f)
        val forceSub = hasAnnot[claw.subcommands](p, f)

        val default: Option[Expr[() => Any]] =
          if p.flags.is(Flags.HasDefault) then
            val getter = comp
              .methodMember(s"apply$$default$$${i + 1}")
              .headOption
              .orElse(comp.methodMember(s"$$lessinit$$greater$$default$$${i + 1}").headOption)
            getter.map { dm =>
              '{ () => ${ Ref(comp).select(dm).asExprOf[Any] } }
            }
          else None
        val defaultE: Expr[Option[() => Any]] = default match
          case Some(d) => '{ Some($d) }
          case None    => '{ None }

        val (isOpt, innerT) = ft match
          case AppliedType(tc, List(a)) if tc.typeSymbol == defn.OptionClass => (true, a.dealias)
          case _                                                             => (false, ft)

        val innerSym = innerT.typeSymbol
        val repeated = repeatedOf(innerT)
        val treatAsSub =
          forceSub || (repeated.isEmpty && isSumSym(innerSym) && userReader(innerT).isEmpty && !allSingleton(innerSym))

        if treatAsSub then
          if !isSumSym(innerSym) then
            report.errorAndAbort(
              s"@subcommands on field '${f.name}' of ${sym.name} requires an enum or sealed trait type, found ${innerT.show}"
            )
          if isPositional then
            report.errorAndAbort(
              s"Field '${f.name}' of ${sym.name}: @positional cannot be combined with a subcommand field"
            )
          if subGroup.nonEmpty then
            report.errorAndAbort(
              s"${sym.name} has more than one subcommand field; only one is supported"
            )
          val cases = subCasesExpr(innerT)
          subGroup = Some('{ SubGroup(${ Expr(i) }, ${ Expr(isOpt) }, $defaultE, $cases) })
        else
          val (modeE, metavarE) = repeated match
            case Some((elem, fromList)) =>
              if isOpt then
                report.errorAndAbort(
                  s"Field '${f.name}' of ${sym.name}: Option of a collection is not supported; use a plain collection (empty when absent)"
                )
              val (readFn, mv) = readerOrEnumRead(elem, f.name, sym.name)
              ('{ Mode.Repeated($readFn, $fromList) }: Expr[Mode], mv)
            case None =>
              if !isOpt && !isPositional && innerT =:= TypeRepr.of[Boolean] then
                ('{ Mode.Flag }: Expr[Mode], Expr("bool"))
              else
                val (readFn, mv) = readerOrEnumRead(innerT, f.name, sym.name)
                ('{ Mode.Single($readFn, ${ Expr(isOpt) }) }: Expr[Mode], mv)

          if isPositional then
            if shortName.nonEmpty then
              report.errorAndAbort(
                s"Field '${f.name}' of ${sym.name}: @short cannot be combined with @positional"
              )
            val kind = repeated match
              case Some(_)                           => "repeated"
              case None if isOpt || default.nonEmpty => "optional"
              case None                              => "required"
            posKinds += ((longName, kind))
            posExprs += '{
              PosSpec(${ Expr(longName) }, ${ Expr(helpText) }, $metavarE, ${ Expr(i) }, $modeE, $defaultE)
            }
          else
            if longName == "help" then
              report.errorAndAbort(s"Field '${f.name}' of ${sym.name}: option name 'help' is reserved")
            if shortName.contains('h') then
              report.errorAndAbort(s"Field '${f.name}' of ${sym.name}: short option 'h' is reserved for help")
            if !longNames.add(longName) then
              report.errorAndAbort(s"Duplicate option name '--$longName' in ${sym.name}")
            shortName.foreach { c =>
              if !shortNames.add(c) then
                report.errorAndAbort(s"Duplicate short option '-$c' in ${sym.name}")
            }
            val shortE: Expr[Option[Char]] = shortName match
              case Some(c) => '{ Some(${ Expr(c) }) }
              case None    => '{ None }
            optExprs += '{
              OptSpec(
                ${ Expr(longName) },
                $shortE,
                ${ Expr(helpText) },
                $metavarE,
                ${ Expr(i) },
                $modeE,
                $defaultE
              )
            }
      }

      // positional ordering rules: required* optional* repeated?
      val kinds = posKinds.result()
      kinds.zipWithIndex.foreach { case ((nm, kind), idx) =>
        if kind == "repeated" && idx != kinds.length - 1 then
          report.errorAndAbort(
            s"Positional '$nm' of ${sym.name}: a repeated positional must be the last positional field"
          )
        if kind == "required" && kinds.take(idx).exists(_._2 != "required") then
          report.errorAndAbort(
            s"Positional '$nm' of ${sym.name}: required positionals must come before optional ones"
          )
      }
      if subGroup.nonEmpty && kinds.nonEmpty then
        report.errorAndAbort(
          s"${sym.name} mixes positional fields with a subcommand field; that is ambiguous and not supported"
        )

      val subE: Expr[Option[SubGroup]] = subGroup match
        case Some(g) => '{ Some($g) }
        case None    => '{ None }

      val fieldTypes = fields.map(f => tpe.memberType(f).widen)
      val buildE: Expr[Array[Any] => Any] = '{ (arr: Array[Any]) =>
        ${ buildApply(tpe, comp, fieldTypes, 'arr) }
      }

      '{
        Command(
          ${ Expr(desc) },
          ${ Expr.ofList(optExprs.result()) }.toVector,
          ${ Expr.ofList(posExprs.result()) }.toVector,
          $subE,
          $buildE,
          ${ Expr(fields.length) }
        )
      }

    private def buildApply(
        tpe: TypeRepr,
        comp: Symbol,
        fieldTypes: List[TypeRepr],
        arr: Expr[Array[Any]]
    ): Expr[Any] =
      val n = fieldTypes.length
      val applyM = comp
        .methodMember("apply")
        .find(m => m.paramSymss.map(_.count(_.isTerm)).sum == n && !m.paramSymss.exists(_.exists(_.isType)))
        .getOrElse(
          report.errorAndAbort(s"Could not find a suitable apply method on companion of ${tpe.show}")
        )
      val args = fieldTypes.zipWithIndex.map { (t, i) =>
        t.asType match
          case '[ta] => '{ $arr(${ Expr(i) }).asInstanceOf[ta] }.asTerm
      }
      Ref(comp).select(applyM).appliedToArgs(args).asExprOf[Any]

    // ---- readers ------------------------------------------------------------

    /** A read function + metavar for a value type: a user/builtin `Reader` if one is
      * in scope, otherwise an auto-derived by-name reader for all-singleton enums.
      */
    private def readerOrEnumRead(
        t: TypeRepr,
        fieldName: String,
        owner: String
    ): (Expr[String => Either[String, Any]], Expr[String]) =
      userReader(t) match
        case Some((r, mv)) => (r, mv)
        case None =>
          val sym = t.typeSymbol
          if isSumSym(sym) && allSingleton(sym) then
            val pairs = Expr.ofList(sym.children.map { ch =>
              val nm = strAnnot[claw.name](ch).getOrElse(kebab(ch.name))
              val ref = singletonValue(ch).get
              '{ (${ Expr(nm) }, ${ ref.asExprOf[Any] }) }
            })
            ('{ Runtime.enumRead($pairs.toVector) }, Expr(enumMetavar(sym)))
          else
            report.errorAndAbort(
              s"No given claw.Reader[${t.show}] found for field '$fieldName' of $owner"
            )

    private def userReader(t: TypeRepr): Option[(Expr[String => Either[String, Any]], Expr[String])] =
      t.asType match
        case '[v] =>
          Expr.summon[Reader[v]].map { r =>
            val fn = '{ (s: String) => $r.read(s): Either[String, Any] }
            (fn, '{ $r.typeName })
          }

    private def enumMetavar(sym: Symbol): String =
      val names = sym.children.map(ch => strAnnot[claw.name](ch).getOrElse(kebab(ch.name)))
      val joined = names.mkString("|")
      if joined.length <= 40 then joined else kebab(sym.name)

    // ---- collections ---------------------------------------------------------

    private lazy val listSym = Symbol.requiredClass("scala.collection.immutable.List")
    private lazy val vectorSym = Symbol.requiredClass("scala.collection.immutable.Vector")
    private lazy val immSeqSym = Symbol.requiredClass("scala.collection.immutable.Seq")
    private lazy val collSeqSym = Symbol.requiredClass("scala.collection.Seq")

    private def repeatedOf(t: TypeRepr): Option[(TypeRepr, Expr[List[Any] => Any])] =
      t match
        case AppliedType(tc, List(a)) =>
          val s = tc.typeSymbol
          if s == listSym then Some((a.dealias, '{ (l: List[Any]) => l }))
          else if s == vectorSym then Some((a.dealias, '{ (l: List[Any]) => l.toVector }))
          else if s == immSeqSym || s == collSeqSym then Some((a.dealias, '{ (l: List[Any]) => l }))
          else None
        case _ => None

    // ---- symbol helpers -------------------------------------------------------

    private def isSumSym(sym: Symbol): Boolean =
      (sym.flags.is(Flags.Enum) && !sym.flags.is(Flags.Case)) ||
        (sym.flags.is(Flags.Sealed) && (sym.flags.is(Flags.Trait) || sym.flags.is(Flags.Abstract)))

    private def singletonValue(ch: Symbol): Option[Term] =
      if ch.isTerm then Some(Ref(ch))
      else if ch.flags.is(Flags.Module) then Some(Ref(ch.companionModule))
      else None

    private def allSingleton(sym: Symbol): Boolean =
      sym.children.nonEmpty && sym.children.forall(ch => singletonValue(ch).nonEmpty)

    // ---- annotations ------------------------------------------------------------

    private def annotTerm[T: Type](sym: Symbol): Option[Term] =
      sym.annotations.find(_.tpe <:< TypeRepr.of[T])

    private def firstAnnotArg[T: Type](syms: Symbol*): Option[Term] =
      syms.iterator.flatMap(s => annotTerm[T](s)).collectFirst { case Apply(_, arg :: Nil) => arg }

    private def strAnnot[T: Type](syms: Symbol*): Option[String] =
      firstAnnotArg[T](syms*).collect { case Literal(StringConstant(v)) => v }

    private def charAnnot[T: Type](syms: Symbol*): Option[Char] =
      firstAnnotArg[T](syms*).collect { case Literal(CharConstant(v)) => v }

    private def hasAnnot[T: Type](syms: Symbol*): Boolean =
      syms.exists(s => annotTerm[T](s).nonEmpty)

    // ---- naming ---------------------------------------------------------------

    private def kebab(s: String): String =
      val b = new StringBuilder
      s.zipWithIndex.foreach { (c, i) =>
        if c.isUpper then
          if i > 0 && !s(i - 1).isUpper then b += '-'
          b += c.toLower
        else b += c
      }
      b.result()
