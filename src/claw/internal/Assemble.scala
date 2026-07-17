package claw.internal

import claw.Parser
import steps.result.Result
import scala.collection.mutable

/** One case of a derived sum: either a singleton value or a nested command. */
enum SubEntry:
  case Leaf(value: Any)
  case Node(parser: () => Parser[?])

/** Builds the runtime `Command` model from what inline derivation collected — one
  * `(Parser, optional)` pair per field, dispatched on the parser's schema.
  * Structural validation happens here, when the `Parser` instance is constructed.
  */
object Assemble:

  def kebab(s: String): String =
    val b = new StringBuilder
    s.zipWithIndex.foreach { (c, i) =>
      if c.isUpper then
        if i > 0 && !s(i - 1).isUpper then b += '-'
        b += c.toLower
      else b += c
    }
    b.result()

  def progName(label: String, onType: TargetAnnots): String =
    onType.name.map(_.value).getOrElse(kebab(label))

  private def readFn(p: Parser[?]): String => Result[Any, String] =
    s => p.asInstanceOf[Parser[Any]].read(s)

  private def invalid(msg: String): Nothing =
    throw new IllegalArgumentException(s"claw: invalid CLI definition: $msg")

  /** By-name parser for an all-singleton enum, honoring `@name` on cases. */
  def enumValueParser(
      typeLabel: String,
      caseLabels: List[String],
      values: List[Any],
      perCase: List[TargetAnnots]
  ): Parser[Any] =
    val names = caseLabels.zipWithIndex.map { (l, i) =>
      perCase.lift(i).flatMap(_.name).map(_.value).getOrElse(kebab(l))
    }
    val joined = names.mkString("|")
    val typeName = if joined.length <= 40 then joined else kebab(typeLabel)
    Runtime.enumParser(typeName, names.zip(values).toVector)

  /** The command view of a value-shaped parser: one positional argument. */
  def singleValueCommand(p: Parser[?]): Command =
    val mode = p.schema match
      case Parser.Schema.Value(_, _) =>
        Mode.Single(readFn(p), false)
      case Parser.Schema.Flag(_, fromValue) =>
        fromValue match
          case Some(f) => Mode.Single(f.asInstanceOf[String => Result[Any, String]], false)
          case None    => invalid("a flag parser without a value parser cannot be run standalone")
      case Parser.Schema.Repeated(element, build) =>
        Mode.Repeated(readFn(element), build.asInstanceOf[List[Any] => Result[Any, String]])
      case Parser.Schema.Command(impl, _) =>
        return impl
    Command("", Vector.empty, Vector(PosSpec("value", "", p.typeName, 0, mode, None)), None, Nil, arr => arr(0), 1)

  def sum(caseLabels: List[String], annots: Annots.Sum[?], entries: List[SubEntry]): Command =
    val cases = entries.zipWithIndex.map { (e, i) =>
      val anns = annots.perCase.lift(i).getOrElse(TargetAnnots.empty)
      val help = anns.help.map(_.value).getOrElse("")
      val cmd = e match
        case SubEntry.Leaf(v) => Command.leaf(v, help)
        case SubEntry.Node(p) => p().command
      SubCase(anns.name.map(_.value).getOrElse(kebab(caseLabels(i))), help, cmd)
    }
    Command(
      annots.onType.help.map(_.value).getOrElse(""),
      Vector.empty,
      Vector.empty,
      Some(SubGroup(0, false, None, cases.toVector)),
      Nil,
      arr => arr(0),
      1
    )

  def product(
      labels: List[String],
      fields: List[(Parser[?], Boolean)],
      defaults: Defaults[?],
      annots: Annots.Product[?],
      build: Array[Any] => Any
  ): Command =
    val n = labels.length
    val opts = Vector.newBuilder[OptSpec]
    val poss = Vector.newBuilder[PosSpec]
    var subGroup: Option[SubGroup] = None
    val splices = List.newBuilder[Splice]
    var storage = n // spliced children's specs live past the parent's own slots
    val longSeen = mutable.Set.empty[String]
    val shortSeen = mutable.Set.empty[Char]
    // (name, kind) where kind is "required" | "optional" | "repeated"
    val posKinds = mutable.ListBuffer.empty[(String, String)]

    for i <- 0 until n do
      val anns = annots.perField.lift(i).getOrElse(FieldAnnots.empty)
      val label = labels(i)
      val long = anns.name.map(_.value).getOrElse(kebab(label))
      val help = anns.help.map(_.value).getOrElse("")
      val short = anns.short.map(_.value)
      val default: Option[() => Any] =
        if defaults.hasDefault(i) then Some(() => defaults.defaultArgument(i)) else None
      val (fieldParser, optional) = fields(i)

      def addOpt(metavar: String, mode: Mode): Unit =
        if long == "help" then invalid(s"field '$label': option name 'help' is reserved")
        if short.contains('h') then invalid(s"field '$label': short option 'h' is reserved for help")
        if !longSeen.add(long) then invalid(s"duplicate option name '--$long'")
        short.foreach(c => if !shortSeen.add(c) then invalid(s"duplicate short option '-$c'"))
        opts += OptSpec(long, short, help, metavar, i, mode, default)

      def addPos(metavar: String, mode: Mode, kind: String): Unit =
        if short.nonEmpty then invalid(s"field '$label': @short cannot be combined with @positional")
        posKinds += ((long, kind))
        poss += PosSpec(long, help, metavar, i, mode, default)

      fieldParser.schema match
        case Parser.Schema.Command(inner, _) =>
          if anns.positional then
            invalid(s"field '$label': @positional cannot be combined with a command-shaped Parser")
          inner.sub match
            case Some(group) =>
              // sum-shaped: nested subcommands
              if subGroup.nonEmpty then invalid("only one subcommand field is supported per command")
              subGroup = Some(SubGroup(i, optional, default, group.cases))
            case None =>
              // product-shaped: splice the group's options into this command
              if optional then
                invalid(s"field '$label': Option of a spliced options group is not supported")
              if inner.positionals.nonEmpty then
                invalid(s"field '$label': a spliced options group cannot contain positional fields")
              inner.opts.foreach { o =>
                if !longSeen.add(o.long) then
                  invalid(s"duplicate option name '--${o.long}' (from options group '$label')")
                o.short.foreach { c =>
                  if !shortSeen.add(c) then
                    invalid(s"duplicate short option '-$c' (from options group '$label')")
                }
                opts += o.copy(index = storage + o.index)
              }
              splices += Splice(i, storage, inner)
              storage += inner.arity

        case Parser.Schema.Value(typeName, _) =>
          val mode = Mode.Single(readFn(fieldParser), optional)
          if anns.positional then
            addPos(typeName, mode, if optional || default.nonEmpty then "optional" else "required")
          else addOpt(typeName, mode)

        case Parser.Schema.Flag(fromCount, fromValue) =>
          val fc = fromCount.asInstanceOf[Int => Result[Any, String]]
          val fv = fromValue.map(_.asInstanceOf[String => Result[Any, String]])
          if anns.positional || optional then
            // no occurrence-count semantics here; fall back to explicit values
            fv match
              case Some(f) =>
                val mode = Mode.Single(f, optional)
                if anns.positional then
                  addPos("value", mode, if optional || default.nonEmpty then "optional" else "required")
                else addOpt("value", mode)
              case None =>
                val where = if optional then "inside Option" else "positionally"
                invalid(s"field '$label': a flag Parser without a value parser cannot be used $where")
          else addOpt("", Mode.Flag(fc, fv))

        case Parser.Schema.Repeated(element, buildList) =>
          if optional then
            invalid(s"field '$label': Option of a repeated Parser is not supported")
          element.schema match
            case _: Parser.Schema.Value[?] => ()
            case _ =>
              invalid(s"field '$label': repeated Parsers require a single-value element Parser")
          val fromList = buildList.asInstanceOf[List[Any] => Result[Any, String]]
          val mode = Mode.Repeated(readFn(element), fromList)
          if anns.positional then addPos(element.typeName, mode, "repeated")
          else addOpt(element.typeName, mode)
    end for

    val kinds = posKinds.toList
    kinds.zipWithIndex.foreach { case ((nm, kind), idx) =>
      if kind == "repeated" && idx != kinds.length - 1 then
        invalid(s"positional '$nm': a repeated positional must be the last positional field")
      if kind == "required" && kinds.take(idx).exists(_._2 != "required") then
        invalid(s"positional '$nm': required positionals must come before optional ones")
    }
    if subGroup.nonEmpty && kinds.nonEmpty then
      invalid("mixing positional fields with a subcommand field is ambiguous and not supported")

    val allSplices = splices.result()
    // `build` expects exactly the parent's own field slots
    val fullBuild: Array[Any] => Any =
      if allSplices.isEmpty then build else arr => build(arr.take(n))
    Command(
      annots.onType.help.map(_.value).getOrElse(""),
      opts.result(),
      poss.result(),
      subGroup,
      allSplices,
      fullBuild,
      storage
    )
