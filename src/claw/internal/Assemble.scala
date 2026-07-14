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

  /** Single-valued field. `subFallback` is a parser for the field's type when it is an
    * enum/sealed trait, so `@subcommands` can switch the field to command semantics.
    */
  case Value(reader: Reader[?], subFallback: Option[() => Parser[?]], optional: Boolean)

  /** Enum/sealed-trait field parsed as nested subcommands. */
  case Sub(parser: () => Parser[?], optional: Boolean)

  /** Collection field; `fromList` converts the accumulated values. */
  case Repeated(reader: Reader[?], fromList: List[Any] => Any)

  def asOptional: Shape = this match
    case Value(r, s, _) => Value(r, s, true)
    case Sub(p, _)      => Sub(p, true)
    case other          => other

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

  private def nameOf(annots: List[Any], label: String): String =
    annots.collectFirst { case n: claw.name => n.value }.getOrElse(kebab(label))

  private def helpOf(annots: List[Any]): String =
    annots.collectFirst { case h: claw.help => h.value }.getOrElse("")

  private def shortOf(annots: List[Any]): Option[Char] =
    annots.collectFirst { case s: claw.short => s.value }

  def progName(label: String, onType: List[Any]): String =
    onType.collectFirst { case n: claw.name => n.value }.getOrElse(kebab(label))

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
      perCase: List[List[Any]]
  ): Reader[Any] =
    val names = caseLabels.zipWithIndex.map((l, i) => nameOf(perCase.lift(i).getOrElse(Nil), l))
    val joined = names.mkString("|")
    val typeName = if joined.length <= 40 then joined else kebab(typeLabel)
    Runtime.enumReader(typeName, names.zip(values).toVector)

  def sum(caseLabels: List[String], annots: Annots[?], entries: List[SubEntry]): Command =
    val cases = entries.zipWithIndex.map { (e, i) =>
      val anns = annots.perCase.lift(i).getOrElse(Nil)
      val help = helpOf(anns)
      val cmd = e match
        case SubEntry.Leaf(v)  => Command.leaf(v, help)
        case SubEntry.Node(p)  => commandOf(p())
      SubCase(nameOf(anns, caseLabels(i)), help, cmd)
    }
    Command(
      helpOf(annots.onType),
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
      annots: Annots[?],
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
      val anns = annots.perField.lift(i).getOrElse(Nil)
      val label = labels(i)
      val long = nameOf(anns, label)
      val help = helpOf(anns)
      val short = shortOf(anns)
      val isPositional = anns.exists(_.isInstanceOf[claw.positional])
      val forceSub = anns.exists(_.isInstanceOf[claw.subcommands])
      val default = defs(i)
      val shape = shapes(i)

      val subThunk = shape match
        case Shape.Sub(p, _)        => Some(p)
        case Shape.Value(_, s, _)   => s
        case _                      => None

      if forceSub && subThunk.isEmpty then
        invalid(s"@subcommands on field '$label' requires an enum or sealed trait type")

      val isSub = shape.isInstanceOf[Shape.Sub] || (forceSub && subThunk.nonEmpty)

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

      if isSub then
        if isPositional then invalid(s"field '$label': @positional cannot be combined with a subcommand field")
        if subGroup.nonEmpty then invalid("only one subcommand field is supported per command")
        val optional = shape match
          case Shape.Sub(_, o)      => o
          case Shape.Value(_, _, o) => o
          case _                    => false
        val inner = commandOf(subThunk.get())
        val cases = inner.sub
          .getOrElse(invalid(s"field '$label' does not resolve to a set of commands"))
          .cases
        subGroup = Some(SubGroup(i, optional, default, cases))
      else
        shape match
          case Shape.Flag =>
            if isPositional then
              val kind = if default.nonEmpty then "optional" else "required"
              addPos("bool", Mode.Single(Runtime.parseBool(_), false), kind)
            else addOpt("bool", Mode.Flag)
          case Shape.Value(r, _, optional) =>
            val mode = Mode.Single(readFn(r), optional)
            if isPositional then
              addPos(r.typeName, mode, if optional || default.nonEmpty then "optional" else "required")
            else addOpt(r.typeName, mode)
          case Shape.Repeated(r, fromList) =>
            val mode = Mode.Repeated(readFn(r), fromList)
            if isPositional then addPos(r.typeName, mode, "repeated")
            else addOpt(r.typeName, mode)
          case _: Shape.Sub => () // handled above
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

    Command(helpOf(annots.onType), opts.result(), poss.result(), subGroup, build, n)
