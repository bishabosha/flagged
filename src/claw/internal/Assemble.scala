package claw.internal

import claw.{Parser, Reader}
import steps.result.Result
import scala.collection.mutable

/** Per-field derivation result, produced by inline code from the field's type shape.
  * Runtime data: the summoned `Reader`/`Parser` instances travel inside it.
  */
enum Shape:
  /** `Boolean` field. */
  case Flag

  /** Field parsed by a `Reader`; the reader's schema decides single vs repeated. */
  case Value(reader: Reader[?], optional: Boolean)

  /** Field with its own `Parser`: nested subcommands. */
  case Sub(parser: () => Parser[?], optional: Boolean)

  def asOptional: Shape = this match
    case Value(r, _) => Value(r, true)
    case Sub(p, _)   => Sub(p, true)
    case Flag        => Flag

/** One case of a derived sum: either a singleton value or a nested command. */
enum SubEntry:
  case Leaf(value: Any)
  case Node(parser: () => Parser[?])

/** Builds the runtime `Command` model from what inline derivation collected.
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

  private def commandOf(p: Parser[?]): Command = p.command

  private def readFn(r: Reader[?]): String => Result[Any, String] =
    s => r.asInstanceOf[Reader[Any]].read(s)

  private def invalid(msg: String): Nothing =
    throw new IllegalArgumentException(s"claw: invalid CLI definition: $msg")

  /** By-name reader for an all-singleton enum, honoring `@name` on cases. */
  def enumValueReader(
      typeLabel: String,
      caseLabels: List[String],
      values: List[Any],
      perCase: List[TargetAnnots]
  ): Reader[Any] =
    val names = caseLabels.zipWithIndex.map { (l, i) =>
      perCase.lift(i).flatMap(_.name).map(_.value).getOrElse(kebab(l))
    }
    val joined = names.mkString("|")
    val typeName = if joined.length <= 40 then joined else kebab(typeLabel)
    Runtime.enumReader(typeName, names.zip(values).toVector)

  def sum(caseLabels: List[String], annots: Annots.Sum[?], entries: List[SubEntry]): Command =
    val cases = entries.zipWithIndex.map { (e, i) =>
      val anns = annots.perCase.lift(i).getOrElse(TargetAnnots.empty)
      val help = anns.help.map(_.value).getOrElse("")
      val cmd = e match
        case SubEntry.Leaf(v) => Command.leaf(v, help)
        case SubEntry.Node(p) => commandOf(p())
      SubCase(anns.name.map(_.value).getOrElse(kebab(caseLabels(i))), help, cmd)
    }
    Command(
      annots.onType.help.map(_.value).getOrElse(""),
      Vector.empty,
      Vector.empty,
      Some(SubGroup(0, false, None, cases.toVector)),
      arr => arr(0),
      1
    )

  def product(
      labels: List[String],
      shapes: List[Shape],
      defaults: List[Option[() => Any]],
      annots: Annots.Product[?],
      build: Array[Any] => Any
  ): Command =
    val n = labels.length
    val defs = if defaults.length == n then defaults else List.fill(n)(None)
    val opts = Vector.newBuilder[OptSpec]
    val poss = Vector.newBuilder[PosSpec]
    var subGroup: Option[SubGroup] = None
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
      val default = defs(i)

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

      shapes(i) match
        case Shape.Sub(parser, optional) =>
          if anns.positional then
            invalid(s"field '$label': @positional cannot be combined with a subcommand field")
          if subGroup.nonEmpty then invalid("only one subcommand field is supported per command")
          val cases = commandOf(parser()).sub
            .getOrElse(invalid(s"field '$label' does not resolve to a set of commands"))
            .cases
          subGroup = Some(SubGroup(i, optional, default, cases))

        case Shape.Flag =>
          if anns.positional then
            val kind = if default.nonEmpty then "optional" else "required"
            addPos("bool", Mode.Single(Runtime.parseBool(_), false), kind)
          else addOpt("bool", Mode.Flag)

        case Shape.Value(r, optional) =>
          r.schema match
            case Reader.Schema.Value(typeName, _) =>
              val mode = Mode.Single(readFn(r), optional)
              if anns.positional then
                addPos(typeName, mode, if optional || default.nonEmpty then "optional" else "required")
              else addOpt(typeName, mode)
            case Reader.Schema.Repeated(element, buildList) =>
              if optional then
                invalid(s"field '$label': Option of a repeated Reader is not supported")
              element.schema match
                case _: Reader.Schema.Repeated[?, ?] =>
                  invalid(s"field '$label': nested repeated Readers are not supported")
                case _ => ()
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

    Command(annots.onType.help.map(_.value).getOrElse(""), opts.result(), poss.result(), subGroup, build, n)
