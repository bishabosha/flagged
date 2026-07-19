package flagged.internal

import flagged.Parser
import flagged.meta.Defaults
import steps.result.Result
import scala.collection.mutable

/** One case of a derived sum: either a singleton value or a nested command. */
enum SubEntry:
  case Leaf(value: Any)
  case Node(parser: () => Parser[?])

/** The resolved role of one product field: the complete shape × `@positional` × `Option[_]` matrix
  * lives in [[Assemble.resolveField]], which produces exactly one of these. Aggregation into a
  * `Command` then needs only cross-field rules.
  */
private enum Plan:
  case Named(spec: OptSpec)
  case Positional(spec: PosSpec, kind: PosKind)
  case Commands(index: Int, optional: Boolean, default: Option[() => Any], inner: Command)
  case Grouped(index: Int, label: String, inner: Command)
  case Rest(spec: TrailingSpec)

private enum PosKind:
  case Required, Optional, Repeated

/** Everything known about one field before shape resolution. */
private final case class Field(
    index: Int,
    label: String,
    long: String,
    short: Option[Char],
    help: String,
    positional: Boolean,
    hidden: Boolean,
    optional: Boolean,
    default: Option[() => Any],
    parser: Parser[?]
)

/** Builds the runtime `Command` model from what inline derivation collected — one
  * `(Parser, optional)` pair per field, dispatched on the parser's schema.
  *
  * Combination rules that are visible in types (annotations, `Option` wrapping, and the shapes of
  * shape-refined instances) are rejected at compile time in `Derive`; the checks here are the
  * runtime backstop for shape-erased instances, plus the inherently value-level rules (name
  * uniqueness, positional ordering).
  */
object Assemble:

  def kebab(s: String): String =
    val b = new StringBuilder
    s.zipWithIndex.foreach { (c, i) =>
      // word boundaries: before an upper after non-upper, and at both edges of a digit run
      val prev     = if i > 0 then Some(s(i - 1)) else None
      val boundary =
        (c.isUpper && prev.exists(!_.isUpper)) ||
          (c.isDigit && prev.exists(p => !p.isDigit)) ||
          (c.isLetter && prev.exists(_.isDigit))
      if boundary && prev.exists(_ != '-') then b += '-'
      b += c.toLower
    }
    b.result()

  def progName(label: String, onType: TargetAnnots): String =
    onType.name.getOrElse(kebab(label))

  private def readFn(p: Parser[?]): String => Result[Any, String] =
    s => p.asInstanceOf[Parser[Any]].read(s)

  private def invalid(msg: String): Nothing =
    throw new IllegalArgumentException(s"flagged: invalid CLI definition: $msg")

  /** By-name parser for an all-singleton enum, honoring `@name` on cases. */
  def enumValueParser(
      typeLabel: String,
      caseLabels: List[String],
      values: List[Any],
      perCase: IndexedSeq[TargetAnnots]
  ): Parser.Enumerated[Any] =
    val names = caseLabels.zipWithIndex.map { (l, i) =>
      perCase(i).name.getOrElse(kebab(l))
    }
    val joined   = names.mkString("|")
    val typeName = if joined.length <= 40 then joined else kebab(typeLabel)
    Runtime.enumParser(typeName, names.zip(values).toVector)

  /** The command view of a value-shaped parser: one positional argument. */
  def singleValueCommand(p: Parser[?]): Command =
    val mode = p match
      case _: Parser.Value[?]       => Mode.Single(readFn(p), false)
      case vf: Parser.ValuedFlag[?] =>
        Mode.Single(vf.fromValue.asInstanceOf[String => Result[Any, String]], false)
      case _: Parser.Flag[?] =>
        invalid("a flag parser without a value parser cannot be run standalone")
      case r: Parser.Repeated[?] =>
        Mode.Repeated(
          s => r.element.parse(s),
          r.build.asInstanceOf[List[Any] => Result[Any, String]]
        )
      case t: Parser.Trailing[?] =>
        val spec =
          TrailingSpec(0, "", t.build, false, None)
        return Command(
          "",
          Vector.empty,
          Vector.empty,
          None,
          Some(spec),
          Nil,
          arr => Result.Ok(arr(0)),
          1
        )
      case c: Parser.Command[?] =>
        return c.impl
    Command(
      "",
      Vector.empty,
      Vector(PosSpec("value", "", p.typeName, 0, mode, None)),
      None,
      None,
      Nil,
      arr => Result.Ok(arr(0)),
      1
    )

  def sum(caseLabels: List[String], annots: Annots.Sum[?], entries: List[SubEntry]): Command =
    val cases = entries.zipWithIndex.map { (e, i) =>
      val anns = annots.perCase(i)
      val help = anns.help.getOrElse("")
      val cmd  = e match
        case SubEntry.Leaf(v) => Command.leaf(v, help)
        case SubEntry.Node(p) => p().command
      SubCase(anns.name.getOrElse(kebab(caseLabels(i))), help, cmd, anns.hidden)
    }
    Command(
      annots.onType.help.getOrElse(""),
      Vector.empty,
      Vector.empty,
      Some(SubGroup(0, false, None, cases.toVector)),
      None,
      Nil,
      arr => Result.Ok(arr(0)),
      1,
      annots.onType.version
    )

  // ---- product assembly -------------------------------------------------------

  def product(
      labels: List[String],
      fields: List[(Parser[?], Boolean)],
      defaults: Defaults[?],
      annots: Annots.Product[?],
      build: Array[Any] => Result[Any, String]
  ): Command =
    val n     = labels.length
    val plans = (0 until n).toList.map { i =>
      val anns          = annots.perField(i)
      val (parser, opt) = fields(i)
      resolveField(
        Field(
          index = i,
          label = labels(i),
          long = anns.name.getOrElse(kebab(labels(i))),
          short = anns.short,
          help = anns.help.getOrElse(""),
          positional = anns.positional,
          hidden = anns.hidden,
          optional = opt,
          default =
            if defaults.hasDefault(i) then Some(() => defaults.defaultArgument(i)) else None,
          parser = parser
        )
      )
    }
    combine(n, plans, annots.onType, build)

  /** The complete field matrix: one parser shape × `@positional` × `Option[_]` case at a time, each
    * producing a [[Plan]] or a construction error.
    */
  /** Translate one field into its [[Plan]]. Shape x annotation combinations are guaranteed by the
    * compile-time layer in `Derive`; only value-level rules (reserved/duplicate names via
    * kebab-cased labels, splice contents) are checked here.
    */
  private def resolveField(f: Field): Plan =
    def bad(msg: String): Nothing = invalid(s"field '${f.label}': $msg")
    def posKind                   =
      if f.optional || f.default.nonEmpty then PosKind.Optional else PosKind.Required
    def named(metavar: String, mode: Mode): Plan =
      if f.long == "help" then bad("option name 'help' is reserved")
      Plan.Named(OptSpec(f.long, f.short, f.help, metavar, f.index, mode, f.default, f.hidden))
    def positional(metavar: String, mode: Mode, kind: PosKind): Plan =
      Plan.Positional(PosSpec(f.long, f.help, metavar, f.index, mode, f.default), kind)

    f.parser match
      case cg: Parser.CommandGroup[?] =>
        Plan.Commands(f.index, f.optional, f.default, cg.impl)

      case c: Parser.Command[?] =>
        val inner = c.impl
        if inner.positionals.nonEmpty then
          bad("a spliced options group cannot contain positional fields")
        if inner.trailing.nonEmpty then
          bad("a spliced options group cannot contain a trailing field")
        Plan.Grouped(f.index, f.label, inner)

      case vf: Parser.ValuedFlag[?] =>
        val fv = vf.fromValue.asInstanceOf[String => Result[Any, String]]
        if f.positional then positional("value", Mode.Single(fv, f.optional), posKind)
        else
          named(
            "",
            Mode.Flag(vf.fromCount.asInstanceOf[Int => Result[Any, String]], Some(fv), f.optional)
          )

      case fl: Parser.Flag[?] =>
        named("", Mode.Flag(fl.fromCount.asInstanceOf[Int => Result[Any, String]], None, false))

      case v: Parser.Value[?] =>
        val mode = Mode.Single(readFn(f.parser), f.optional)
        if f.positional then positional(v.typeName, mode, posKind) else named(v.typeName, mode)

      case r: Parser.Repeated[?] =>
        val mode = Mode.Repeated(
          s => r.element.parse(s),
          r.build.asInstanceOf[List[Any] => Result[Any, String]]
        )
        if f.positional then positional(r.typeName, mode, PosKind.Repeated)
        else named(r.typeName, mode)

      case t: Parser.Trailing[?] =>
        Plan.Rest(TrailingSpec(f.index, f.help, t.build, f.optional, f.default))

  /** Cross-field aggregation: name uniqueness, at-most-one subcommand/trailing field, positional
    * ordering, splice storage layout.
    */
  private def combine(
      n: Int,
      plans: List[Plan],
      onType: TargetAnnots,
      build: Array[Any] => Result[Any, String]
  ): Command =
    val names = NameRegistry()

    val opts     = Vector.newBuilder[OptSpec]
    val poss     = Vector.newBuilder[PosSpec]
    val posKinds = List.newBuilder[(String, PosKind)]
    val splices  = List.newBuilder[Splice]
    var sub      = Option.empty[SubGroup]
    var trailing = Option.empty[TrailingSpec]
    var storage  = n // spliced children's specs live past the parent's own slots

    plans.foreach {
      case Plan.Named(spec) =>
        names.register(spec.long, spec.short, from = None)
        opts += spec
      case Plan.Positional(spec, kind) =>
        posKinds += ((spec.name, kind))
        poss += spec
      case Plan.Commands(index, optional, default, inner) =>
        if sub.nonEmpty then invalid("only one subcommand field is supported per command")
        sub = Some(SubGroup(index, optional, default, inner.sub.get.cases))
      case Plan.Grouped(index, label, inner) =>
        inner.opts.foreach { o =>
          names.register(o.long, o.short, from = Some(label))
          opts += o.copy(index = storage + o.index)
        }
        splices += Splice(index, storage, inner)
        storage += inner.arity
      case Plan.Rest(spec) =>
        if trailing.nonEmpty then invalid("only one trailing field is supported per command")
        trailing = Some(spec)
    }

    checkPositionalOrder(posKinds.result())
    if sub.nonEmpty && poss.result().nonEmpty then
      invalid("mixing positional fields with a subcommand field is ambiguous and not supported")

    val allSplices = splices.result()
    // `build` expects exactly the parent's own field slots
    val fullBuild: Array[Any] => Result[Any, String] =
      if allSplices.isEmpty then build else arr => build(arr.take(n))
    Command(
      onType.help.getOrElse(""),
      opts.result(),
      poss.result(),
      sub,
      trailing,
      allSplices,
      fullBuild,
      storage,
      onType.version
    )

  /** Long / short option names claimed so far; duplicates are construction errors. */
  private final class NameRegistry:
    private val longs  = mutable.Set.empty[String]
    private val shorts = mutable.Set.empty[Char]

    def register(long: String, short: Option[Char], from: Option[String]): Unit =
      val origin = from.fold("")(l => s" (from options group '$l')")
      if !longs.add(long) then invalid(s"duplicate option name '--$long'$origin")
      short.foreach { c =>
        if !shorts.add(c) then invalid(s"duplicate short option '-$c'$origin")
      }

  private def checkPositionalOrder(kinds: List[(String, PosKind)]): Unit =
    kinds.zipWithIndex.foreach { case ((nm, kind), idx) =>
      if kind == PosKind.Repeated && idx != kinds.length - 1 then
        invalid(s"positional '$nm': a repeated positional must be the last positional field")
      if kind == PosKind.Required && kinds.take(idx).exists(_._2 != PosKind.Required) then
        invalid(s"positional '$nm': required positionals must come before optional ones")
    }
