package flagged.internal

import language.experimental.captureChecking
import language.experimental.separationChecking

import flagged.Parser
import flagged.meta.Defaults
import scala.annotation.publicInBinary
import steps.result.Result
import steps.result.Result.eval.ok
import scala.collection.immutable.IntMap
import scala.collection.mutable

/** One case of a derived sum: a singleton value, a nested command, or a parser to take one from. */
private[flagged] enum SubEntry:
  case Leaf(value: Any)
  case Node(parser: Parser[?])

  /** An already-assembled command — [[DeriveMethods]] builds these directly, since a `Parser`
    * wrapper would only be unwrapped again here, its `prog` discarded.
    */
  case Cmd(command: Command)

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
@publicInBinary private[flagged] object Assemble:

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

  /** By-name parser for an all-singleton enum, honoring `@cmd(name)` on cases. */
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
      case v: Parser.Value[?]       => Mode.Single(v, false)
      case vf: Parser.ValuedFlag[?] => Mode.Single(vf, false)
      case pr: Parser.Product[?]    => Mode.Product(pr, false)
      case _: Parser.Flag[?]        =>
        invalid("a flag parser without a value parser cannot be run standalone")
      case r: Parser.Repeated[?] =>
        Mode.Repeated(r)
      case t: Parser.Trailing[?] =>
        val spec =
          TrailingSpec(0, "", t, false, None)
        return Command(
          "",
          Command.noOpts,
          Command.noPos,
          None,
          Some(spec),
          Command.noSplices,
          (arr, base, i) =>
            Result.task:
              arr(i) = arr(base)
          ,
          1
        )
      case c: Parser.Command[?] =>
        return c.impl
    val spec = PosSpec("value", "", p.typeName, 0, mode, None)
    Command(
      "",
      Command.noOpts,
      frozen(Array(spec)),
      None,
      None,
      Command.noSplices,
      (arr, base, i) =>
        Result.task:
          arr(i) = arr(base)
      ,
      1
    )

  def sum(
      caseLabels: IndexedSeq[String],
      annots: Annots.Sum[?],
      entries: IndexedSeq[SubEntry],
      version: Option[() -> String]
  ): Command =
    val cases = Vector.tabulate(entries.length) { i =>
      val anns = annots.caseAnnots(i)
      val help = anns.help.getOrElse("")
      val cmd  = entries(i) match
        case SubEntry.Leaf(v) => Command.leaf(v, help)
        case SubEntry.Node(p) => p.command
        case SubEntry.Cmd(c)  => c
      SubCase(anns.name.getOrElse(kebab(caseLabels(i))), help, cmd, anns.hidden, anns.aliases)
    }
    // kebab-derived command names can collide only at the value level (constant @cmd name/alias
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
      Command.noOpts,
      Command.noPos,
      Some(SubGroup(0, false, None, cases, defaultCase)),
      None,
      Command.noSplices,
      (arr, base, i) =>
        Result.task:
          arr(i) = arr(base)
      ,
      1,
      version
    )

  // ---- product assembly -------------------------------------------------------

  def fieldsBuilder(n: Int, defaults: Defaults[?]): FieldsBuilder^ = FieldsBuilder(n, defaults)

  /** The runtime half of product derivation, fused: the inline field walk calls [[addField]] once
    * per field in declaration order, and each call resolves the field's role from its parser's
    * shape and writes the finished spec straight into the command being assembled — no intermediate
    * per-field records. Shape × annotation combinations are guaranteed by the compile-time layer in
    * `Derive`; only value-level rules (reserved/duplicate names via kebab-cased labels, positional
    * ordering) are checked here. [[result]] closes the command.
    */
  final class FieldsBuilder private[Assemble] (n: Int, defaults: Defaults[?])
      extends caps.Mutable:
    // the long lookup the finished Command parses with, built as fields arrive — duplicate
    // detection is the map insert itself
    private var lookup: java.util.HashMap[String, OptSpec]      = null
    // spec staging: lazily allocated at the field-count bound, copied once to exact size in
    // `resultInto` — the same single grow-free copy an ArrayBuilder would make, but the staging
    // array stays exclusive to this builder, so the copy can be frozen into pure spec storage
    // (`ArrayBuilder.result()` is typed `^{any.rd}` because it may alias the builder's internal
    // buffer, which `caps.freeze` rightly refuses to consume)
    private var opts: Array[OptSpec]^ = null
    private var optsN                 = 0
    private var shorts: IntMap[OptSpec] = IntMap.empty
    private var poss: Array[PosSpec]^ = null
    private var possN                 = 0
    private var spls: Array[Splice]^ = null
    private var splsN                = 0
    private var sub: SubGroup                                   = null
    private var trailing: TrailingSpec                          = null
    private var storage         = n // spliced children's specs live past the parent's own slots
    private var index           = 0
    private var optionalPosSeen = false

    private def origin(from: String): String =
      if from == null then "" else s" (from options group '$from')"

    /** Register `spec` under `key` (`--`-prefixed); the insert doubles as duplicate detection. */
    private update def putName(key: String, spec: OptSpec, from: String): Unit =
      if lookup == null then lookup = new java.util.HashMap
      if lookup.put(key, spec) != null then invalid(s"duplicate option name '$key'${origin(from)}")

    private update def addOpt(spec: OptSpec, from: String): Unit =
      // unlike positionals and splices, opts can outnumber the parent's own fields: a spliced
      // group contributes all of its options while occupying a single field slot
      if opts == null then opts = new Array[OptSpec](if n < 4 then 4 else n)
      else if optsN == opts.length then
        val grown: Array[OptSpec]^ = new Array[OptSpec](opts.length * 2)
        Array.copy(opts, 0, grown, 0, optsN)
        opts = grown
      opts(optsN) = spec
      optsN += 1
      putName(spec.longDisplay, spec, from)
      var ai = 0
      while ai < spec.aliases.length do
        putName("--" + spec.aliases(ai), spec, from)
        ai += 1
      if spec.short >= 0 then
        val c = spec.short.toChar
        if shorts.contains(spec.short) then invalid(s"duplicate short option '-$c'${origin(from)}")
        shorts = shorts.updated(spec.short, spec)

    update def addField(label: String, parser: Parser[?], optional: Boolean, anns: FieldAnnots): Unit =
      val i = index
      index += 1
      val default =
        if defaults.hasDefault(i) then Some(() => defaults.defaultArgument(i)) else None
      def bad(msg: String): Nothing                = invalid(s"field '$label': $msg")
      def named(metavar: String, mode: Mode): Unit =
        val long = anns.name.getOrElse(kebab(label))
        if long == "help" then bad("option name 'help' is reserved")
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
          ),
          from = null
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
        if poss == null then poss = new Array[PosSpec](n)
        poss(possN) = PosSpec(
          anns.name.getOrElse(kebab(label)),
          anns.help.getOrElse(""),
          metavar,
          i,
          mode,
          default
        )
        possN += 1
      def requiredPos = !(optional || default.nonEmpty)

      parser match
        case cg: Parser.CommandGroup[?] =>
          // at most one: derivation checks GroupBit x GroupBit at compile time
          val g = cg.impl.sub.get
          sub = SubGroup(i, optional, default, g.cases, g.defaultCase)

        case sh: Parser.Shared[?] =>
          // splice-content invariants need no check here: every Shared instance descends from
          // checked derivation (the factory is private), which rejects positionals, trailing,
          // subcommands, and greedy options at compile time
          val inner  = sh.impl
          val prefix = anns.name
          inner.opts.foreach { o =>
            // a prefixed splice renames its options (--net-host) and drops their short aliases,
            // so the same group can be spliced more than once
            val long    = prefix.fold(o.long)(pre => s"$pre-${o.long}")
            val short   = if prefix.isEmpty then o.short else MaybeChar.empty
            val aliases = o.aliases.map(a => prefix.fold(a)(pre => s"$pre-$a"))
            addOpt(
              o.copy(
                long = long,
                short = short,
                index = storage + o.index,
                group = o.group.orElse(anns.group),
                aliases = aliases
              ),
              from = label
            )
          }
          if spls == null then spls = new Array[Splice](n)
          spls(splsN) = Splice(i, storage, inner, optional, default)
          splsN += 1
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
          val mode = Mode.Repeated(r, anns.split, anns.greedy)
          if anns.positional then positional(r.typeName, mode, required = false)
          else named(r.typeName, mode)

        case t: Parser.Trailing[?] =>
          // at most one, and never next to positionals or subcommands: compile-checked
          trailing = TrailingSpec(i, anns.help.getOrElse(""), t, optional, default)

    update def resultInto(
        onType: TargetAnnots,
        build: (Array[Any]^, Int, Int) -> Result[Unit, String],
        version: Option[() -> String]
    ): Command =
      // `@version` contributes an implicit hidden option. A null spec keeps its parsing built-in,
      // while registering its name through the same insertion that diagnoses field collisions.
      if version.nonEmpty then putName("--version", null, null)
      val allOpts    = if opts == null then Command.noOpts else frozen(opts.take(optsN))
      val allPos     = if poss == null then Command.noPos else frozen(poss.take(possN))
      val allSplices = if spls == null then Command.noSplices else frozen(spls.take(splsN))

      // `build` receives the whole storage plus the parent's own field count — no trimming
      Command(
        onType.help.getOrElse(""),
        allOpts,
        allPos,
        if sub == null then None else Some(sub),
        if trailing == null then None else Some(trailing),
        allSplices,
        build,
        storage,
        version,
        if lookup == null then Command.noLookup else lookup,
        shorts
      )
