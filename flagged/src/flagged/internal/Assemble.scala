package flagged.internal

import flagged.Parser
import flagged.meta.Defaults
import steps.result.Result
import scala.collection.mutable

/** One case of a derived sum: either a singleton value or a nested command. */
enum SubEntry:
  case Leaf(value: Any)
  case Node(parser: () => Parser[?])

/** Builds the runtime `Command` model as inline derivation walks the fields: the walk feeds a
  * [[Assemble.FieldsBuilder]] one `addField` call per field, which dispatches on the parser's
  * schema and writes the finished spec directly — no intermediate per-field representation.
  *
  * Combination rules that are visible in types (annotations, `Option` wrapping, and the shapes of
  * shape-refined instances) are rejected at compile time in `Derive`, and the command factories are
  * private, so every command descends from a checked derivation. What remains here is the
  * inherently value-level residue: name uniqueness (kebab-derived names), positional ordering
  * (optionality depends on defaults, a term-level fact), and splice storage layout.
  */
object Assemble:

  def kebab(s: String): String =
    // fast path: a label that is already kebab (all lower, no digit runs) is its own name
    var j = 0
    while j < s.length && (s(j).isLower || s(j) == '-') do j += 1
    if j == s.length then return s
    val b = new StringBuilder
    var i = 0
    while i < s.length do
      val c = s(i)
      if i > 0 then
        val p = s(i - 1)
        // word boundaries: before an upper after non-upper, and at both edges of a digit run
        val boundary =
          (c.isUpper && !p.isUpper) || (c.isDigit && !p.isDigit) || (c.isLetter && p.isDigit)
        if boundary && p != '-' then b += '-'
      b += c.toLower
      i += 1
    b.result()

  def progName(label: String, onType: TargetAnnots): String =
    onType.name.getOrElse(kebab(label))

  private def invalid(msg: String): Nothing =
    throw new IllegalArgumentException(s"flagged: invalid CLI definition: $msg")

  /** By-name parser for an all-singleton enum, honoring `@name` on cases. */
  def enumValueParser(
      typeLabel: String,
      caseLabels: IndexedSeq[String],
      values: IndexedSeq[Any],
      annots: Annots.Sum[?]
  ): Parser.Enumerated[Any] =
    val pairs = Vector.tabulate(caseLabels.length) { i =>
      (annots.caseAnnots(i).name.getOrElse(kebab(caseLabels(i))), values(i))
    }
    val joined   = pairs.iterator.map(_(0)).mkString("|")
    val typeName = if joined.length <= 40 then joined else kebab(typeLabel)
    Runtime.enumParser(typeName, pairs)

  /** The command view of a value-shaped parser: one positional argument. */
  def singleValueCommand(p: Parser[?]): Command =
    val mode = p match
      case _: Parser.Value[?]      => Mode.Single(p, false)
      case _: Parser.ValuedFlag[?] => Mode.Single(p, false)
      case pr: Parser.Product[?]   => Mode.Product(pr, false)
      case _: Parser.Flag[?]       =>
        invalid("a flag parser without a value parser cannot be run standalone")
      case r: Parser.Repeated[?] =>
        Mode.Repeated(r)
      case t: Parser.Trailing[?] =>
        val spec =
          TrailingSpec(0, "", t, false, None)
        return Command(
          "",
          Vector.empty,
          Vector.empty,
          None,
          Some(spec),
          Vector.empty,
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
      Vector.empty,
      arr => Result.Ok(arr(0)),
      1
    )

  def sum(
      caseLabels: IndexedSeq[String],
      annots: Annots.Sum[?],
      entries: IndexedSeq[SubEntry],
      version: Option[() => String]
  ): Command =
    val cases = Vector.tabulate(entries.length) { i =>
      val anns = annots.caseAnnots(i)
      val help = anns.help.getOrElse("")
      val cmd  = entries(i) match
        case SubEntry.Leaf(v) => Command.leaf(v, help)
        case SubEntry.Node(p) => p().command
      SubCase(anns.name.getOrElse(kebab(caseLabels(i))), help, cmd, anns.hidden, anns.aliases)
    }
    // kebab-derived command names can collide only at the value level (constant @name/alias
    // duplicates are compile errors); a silent collision would shadow the later command
    val caseNames = mutable.Set.empty[String]
    for c <- cases do
      if !caseNames.add(c.name) then invalid(s"duplicate command name '${c.name}'")
      for n <- c.aliases do if !caseNames.add(n) then invalid(s"duplicate command name '$n'")
    val defaultCase =
      val i = annots.perCase.indexWhere(_.default)
      if i < 0 then None else Some(cases(i))
    Command(
      annots.onType.help.getOrElse(""),
      Vector.empty,
      Vector.empty,
      Some(SubGroup(0, false, None, cases, defaultCase)),
      None,
      Vector.empty,
      arr => Result.Ok(arr(0)),
      1,
      version
    )

  // ---- product assembly -------------------------------------------------------

  def fieldsBuilder(n: Int, defaults: Defaults[?]): FieldsBuilder = FieldsBuilder(n, defaults)

  /** The runtime half of product derivation, fused: the inline field walk calls [[addField]] once
    * per field in declaration order, and each call resolves the field's role from its parser's
    * shape and writes the finished spec straight into the command being assembled — no intermediate
    * per-field records. Shape × annotation combinations are guaranteed by the compile-time layer in
    * `Derive`; only value-level rules (reserved/duplicate names via kebab-cased labels, positional
    * ordering) are checked here. [[result]] closes the command.
    */
  final class FieldsBuilder private[Assemble] (n: Int, defaults: Defaults[?]):
    private val names                                           = NameRegistry()
    private var opts: mutable.Builder[OptSpec, Vector[OptSpec]] = null
    private var poss: mutable.Builder[PosSpec, Vector[PosSpec]] = null
    private var spls: mutable.Builder[Splice, Vector[Splice]]   = null
    private var sub: SubGroup                                   = null
    private var trailing: TrailingSpec                          = null
    private var storage         = n // spliced children's specs live past the parent's own slots
    private var index           = 0
    private var optionalPosSeen = false

    private def addOpt(spec: OptSpec): Unit =
      if opts == null then opts = Vector.newBuilder
      opts += spec

    def addField(label: String, parser: Parser[?], optional: Boolean, anns: FieldAnnots): Unit =
      val i = index
      index += 1
      val default =
        if defaults.hasDefault(i) then Some(() => defaults.defaultArgument(i)) else None
      def bad(msg: String): Nothing                = invalid(s"field '$label': $msg")
      def named(metavar: String, mode: Mode): Unit =
        val long = anns.name.getOrElse(kebab(label))
        if long == "help" then bad("option name 'help' is reserved")
        names.register(long, anns.short, from = None)
        anns.aliases.foreach(a => names.register(a, None, from = None))
        addOpt(
          OptSpec(
            long,
            anns.short,
            anns.help.getOrElse(""),
            metavar,
            i,
            mode,
            default,
            anns.hidden,
            anns.group,
            anns.aliases
          )
        )
      def positional(metavar: String, mode: Mode, required: Boolean): Unit =
        // required-before-optional is inherently value-level: optionality depends on a field
        // default, a term-level fact (repeated-must-be-last is compile-checked)
        if required then
          if optionalPosSeen then
            invalid(
              s"positional '${anns.name.getOrElse(kebab(label))}': required positionals must come before optional ones"
            )
        else optionalPosSeen = true
        if poss == null then poss = Vector.newBuilder
        poss += PosSpec(
          anns.name.getOrElse(kebab(label)),
          anns.help.getOrElse(""),
          metavar,
          i,
          mode,
          default
        )
      def requiredPos = !(optional || default.nonEmpty)

      parser match
        case cg: Parser.CommandGroup[?] =>
          // at most one: derivation checks GroupBit x GroupBit at compile time
          val g = cg.impl.sub.get
          sub = SubGroup(i, optional, default, g.cases, g.defaultCase)

        case sh: Parser.Shared[?] =>
          // splice-content invariants need no check here: every Shared instance descends from
          // checked derivation (the factory is private), which rejects positionals, trailing,
          // subcommands, and @greedy at compile time
          val inner  = sh.impl
          val prefix = anns.name
          inner.opts.foreach { o =>
            // a prefixed splice renames its options (--net-host) and drops their short aliases,
            // so the same group can be spliced more than once
            val long    = prefix.fold(o.long)(pre => s"$pre-${o.long}")
            val short   = if prefix.isEmpty then o.short else None
            val aliases = o.aliases.map(a => prefix.fold(a)(pre => s"$pre-$a"))
            names.register(long, short, from = Some(label))
            aliases.foreach(a => names.register(a, None, from = Some(label)))
            addOpt(
              o.copy(
                long = long,
                short = short,
                index = storage + o.index,
                group = o.group.orElse(anns.group),
                aliases = aliases
              )
            )
          }
          if spls == null then spls = Vector.newBuilder
          spls += Splice(i, storage, inner, optional, default)
          storage += inner.arity

        case c: Parser.Command[?] =>
          bad(
            "a command-shaped Parser cannot be a field: derive Parser.Shared for a spliceable options group (a full command can be embedded as the sole field of a command-group case)"
          )

        case vf: Parser.ValuedFlag[?] =>
          if anns.positional then positional("value", Mode.Single(vf, optional), requiredPos)
          else named("", Mode.Flag(vf, optional))

        case fl: Parser.Flag[?] =>
          named("", Mode.Flag(fl, false))

        case v: Parser.Value[?] =>
          val mode = Mode.Single(v, optional)
          if anns.positional then positional(v.typeName, mode, requiredPos)
          else named(v.typeName, mode)

        case pr: Parser.Product[?] =>
          // the metavar arrives pre-bracketed (`<x> <y>`); help renders it verbatim
          val mode = Mode.Product(pr, optional)
          if anns.positional then positional(pr.helpMetavar, mode, requiredPos)
          else named(pr.helpMetavar, mode)

        case r: Parser.Repeated[?] =>
          val mode = Mode.Repeated(r, anns.split.getOrElse(0), anns.greedy)
          if anns.positional then positional(r.typeName, mode, required = false)
          else named(r.typeName, mode)

        case t: Parser.Trailing[?] =>
          // at most one, and never next to positionals or subcommands: compile-checked
          trailing = TrailingSpec(i, anns.help.getOrElse(""), t, optional, default)

    def result(
        onType: TargetAnnots,
        build: Array[Any] => Result[Any, String],
        version: Option[() => String]
    ): Command =
      val allSplices = if spls == null then Vector.empty[Splice] else spls.result()
      // `build` expects exactly the parent's own field slots
      val fullBuild: Array[Any] => Result[Any, String] =
        if allSplices.isEmpty then build else arr => build(arr.take(n))
      Command(
        onType.help.getOrElse(""),
        if opts == null then Vector.empty else opts.result(),
        if poss == null then Vector.empty else poss.result(),
        if sub == null then None else Some(sub),
        if trailing == null then None else Some(trailing),
        allSplices,
        fullBuild,
        storage,
        version
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
