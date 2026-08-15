package flagged.internal

import language.experimental.captureChecking
import language.experimental.separationChecking

import flagged.{ParseError, ParseResult}
import steps.result.Result
import steps.result.Result.{Err, eval}
import scala.collection.mutable

/** The token-stream parser, in three phases:
  *
  *   1. route and validate each selected command level, stopping before a child when its parent has
  *      errors;
  *   2. after the complete selected chain validates, materialise defaults from leaf to root;
  *   3. build each command into its parent's subcommand slot, invoking an `@cmd` method only during
  *      this final unwind.
  *
  * Per-level mutable state lives in one final [[Frame]]. This avoids the `ObjectRef`, `IntRef`,
  * `BooleanRef`, and captured-lambda objects produced when local helper methods close over the
  * routing loop's variables. Frame methods never perform result-boundary operations: the only
  * parser boundary is the lexical [[Result.task]] in [[run]].
  */
private[flagged] object Engine:

  def run(
      cmd: Command,
      prog: String,
      path: IndexedSeq[String],
      args: IndexedSeq[String],
      from: Int
  ): ParseResult[Any] =
    val root: Frame^ = Frame(cmd, prog, path, args, from, null, null, -1, false)
    Result
      .task:
        var frame: Frame^ = root
        var leaf          = false

        // Validate the complete selected path before evaluating a default or building a command.
        while !leaf do
          val terminal = frame.routeAndValidate()
          if terminal != null then eval.raise(terminal)
          val selected = frame.selectedSub
          if selected == null then leaf = true
          else
            val group = frame.subGroup
            frame = Frame(
              selected.command,
              prog,
              path,
              args,
              frame.selectedFrom,
              frame,
              selected.name,
              group.index,
              group.optional
            )

        // Every frame builds into its own spare result slot — `finishInto` takes one storage
        // array, so there is nothing for separation checking to distinguish — and the value is
        // then copied to the parent's subcommand slot in two sequential single-array statements.
        while frame != null do
          frame.materializeDefaults()
          frame.command.finishInto(frame.values, frame.skips, 0, frame.resultIndex) match
            case Result.Err(msg) => eval.raise(ParseError.Failure(msg, frame.hint))
            case _               => ()
          if frame.parent != null then
            val v = frame.values(frame.resultIndex)
            frame.parent.values(frame.parentOutIndex) = if frame.parentOptional then Some(v) else v
          frame = frame.parent
      .map(_ => root.values(root.resultIndex))

  /** All mutable state for one command level. Methods deliberately use ordinary returns and
    * `Result` matching only; no result boundary may cross into this object.
    */
  private final class Frame(
      val command: Command,
      prog: String,
      rootPath: IndexedSeq[String],
      args: IndexedSeq[String],
      from: Int,
      val parent: Frame^,
      pathName: String,
      val parentOutIndex: Int,
      val parentOptional: Boolean
  ) extends Mentions, caps.Mutable:
    private val n = command.arity

    // one spare slot past the fields: this command's built value lands at `resultIndex`
    val resultIndex: Int    = n
    val values: Array[Any]^ = new Array[Any](n + 1)
    private var seen0: Long           = 0L
    private val seenMore: Array[Long]^ =
      if n <= 64 then null else new Array[Long]((n + 63) >>> 6)
    // Occurrence counts matter only for flags and are allocated on their first occurrence.
    private var flagCounts: Array[Int]^ = null

    // Raw last-wins values stage directly in `values`; only their spelling needs side storage.
    private var lastDisp: Array[String]^ = null

    // Allocated on the first repeated occurrence.
    private var reps: Array[flagged.Parser.Collector[?]^]^ = null

    private var errors: mutable.ArrayBuffer[String] = null
    private var subErrored                          = false
    private var trailSeen                           = false
    private var idx                                 = from
    private var posIdx                              = 0
    private var noMoreOpts                          = false
    private var skipIdx: collection.Set[Int]        = Set.empty

    var selectedSub: SubCase = null
    var selectedFrom         = 0

    val subGroup: SubGroup | Null = command.sub match
      case Some(sub) => sub
      case _         => null
    private val defaultSubCase: SubCase | Null =
      if subGroup == null then null
      else
        subGroup.defaultCase match
          case Some(c) => c
          case _       => null

    private val shortLookup = command.shortLookup
    private val posSpecs    = command.positionals

    private def appendPath(b: mutable.Builder[String, Vector[String]]): Unit =
      if parent != null then parent.appendPath(b)
      else b ++= rootPath
      if pathName != null then b += pathName

    private def path: IndexedSeq[String] =
      val b = Vector.newBuilder[String]
      appendPath(b)
      b.result()

    def hint: String =
      val full = (prog +: path).mkString(" ")
      s"Try '$full --help' for more information."

    private def failure: ParseError.Failure =
      ParseError.Failure(errors.mkString("\n"), hint)

    private update def report(msg: String): Unit =
      if errors == null then errors = mutable.ArrayBuffer.empty[String]
      errors += msg

    def isSeen(index: Int): Boolean =
      if seenMore == null then (seen0 & (1L << index)) != 0
      else (seenMore(index >>> 6) & (1L << (index & 63))) != 0

    private update def markSeen(index: Int): Unit =
      if seenMore == null then seen0 |= 1L << index
      else seenMore(index >>> 6) |= 1L << (index & 63)

    private update def mention(spec: SlotSpec): Unit =
      markSeen(spec.index)
      spec.mode match
        case Mode.Flag(_, _) =>
          if flagCounts == null then flagCounts = new Array[Int](n)
          flagCounts(spec.index) += 1
        case _ => ()

    private update def stage(spec: SlotSpec, raw: String, display: String): Unit =
      if lastDisp == null then lastDisp = new Array[String](n)
      values(spec.index) = raw
      lastDisp(spec.index) = display

    private def staged(spec: SlotSpec): String =
      if lastDisp == null || lastDisp(spec.index) == null then null
      else values(spec.index).asInstanceOf[String]

    private def shortSpec(c: Char): OptSpec =
      shortLookup.getOrElse(c.toInt, null)

    private def isNegativeNumber(s: String): Boolean =
      s.length > 1 && s(0) == '-' &&
        (s(1).isDigit || (s(1) == '.' && s.length > 2 && s(2).isDigit) || s == "-Infinity")

    private def looksLikeOption(s: String): Boolean =
      s.length > 1 && s.startsWith("-") && !isNegativeNumber(s)

    /** Null after reporting when the value is missing. */
    private update def takeValue(display: String): String =
      if idx < args.length then
        val v = args(idx)
        if !looksLikeOption(v) then
          idx += 1
          return v
      report(s"option '$display' requires a value")
      null

    private def isFlag(spec: OptSpec): Boolean = spec.mode match
      case Mode.Flag(_, _) => true
      case _               => false

    private update def addElem(
        spec: SlotSpec,
        parser: flagged.Parser.Repeated[?],
        raw: String,
        display: String
    ): Unit =
      if reps == null then reps = new Array[flagged.Parser.Collector[?]^](n)
      if reps(spec.index) == null then reps(spec.index) = parser.collector()
      reps(spec.index).offer(raw, values, spec.index) match
        case Result.Err(msg) => report(s"invalid value for '$display': $msg")
        case _               => ()

    private update def addSplitElems(
        spec: SlotSpec,
        parser: flagged.Parser.Repeated[?],
        raw: String,
        display: String,
        sep: Char
    ): Unit =
      var start = 0
      while start <= raw.length do
        val cut = raw.indexOf(sep, start)
        val end = if cut == -1 then raw.length else cut
        addElem(spec, parser, raw.substring(start, end), display)
        start = end + 1

    private update def offerProduct(
        spec: SlotSpec,
        parser: flagged.Parser.Product[?],
        display: String,
        first: String
    ): Unit =
      mention(spec)
      val ar      = parser.arity
      val scratch = new Array[Any](ar)
      var failed  = false
      var k       = 0
      while k < ar do
        val tok = if k == 0 && first != null then first else takeValue(display)
        if tok == null then
          failed = true
          k = ar
        else
          parser.elements(k).readInto(tok, scratch, k) match
            case Result.Err(msg) =>
              report(s"invalid value for '$display': $msg")
              failed = true
            case _ => ()
          k += 1
      if !failed then
        parser.buildInto(scratch, values, spec.index) match
          case Result.Err(msg) => report(s"invalid value for '$display': $msg")
          case _               => ()

    private update def offerValue(spec: SlotSpec, raw: String, display: String): Unit =
      mention(spec)
      spec.mode match
        case Mode.Repeated(parser, sep, _) =>
          if sep >= 0 then addSplitElems(spec, parser, raw, display, sep.toChar)
          else addElem(spec, parser, raw, display)
        case Mode.Product(parser, _) =>
          report(s"option '$display' takes ${parser.arity} values, each as its own argument")
        case Mode.Flag(parser, _) if !parser.takesValue =>
          report(s"flag '$display' does not take a value")
        case _ => stage(spec, raw, display)

    private update def offerBare(spec: SlotSpec): Unit =
      mention(spec)
      values(spec.index) = null

    private def findCase(group: SubGroup, token: String): SubCase =
      var i = 0
      while i < group.cases.length do
        val c = group.cases(i)
        if c.name == token || c.aliases.contains(token) then return c
        i += 1
      null

    private update def selectSub(commandCase: SubCase, fromIndex: Int): Unit =
      selectedSub = commandCase
      selectedFrom = fromIndex
      idx = args.length

    private update def handleFree(token: String): Unit =
      if subGroup != null then
        val commandCase = findCase(subGroup, token)
        if commandCase != null then selectSub(commandCase, idx)
        else if defaultSubCase != null then selectSub(defaultSubCase, idx - 1)
        else
          val candidates = Vector.newBuilder[String]
          subGroup.cases.foreach { c =>
            if !c.hidden then
              candidates += c.name
              candidates ++= c.aliases
          }
          val suggestion = Runtime
            .suggest(token, candidates.result())
            .map(s => s" (did you mean '$s'?)")
            .getOrElse("")
          report(s"unknown command '$token'$suggestion")
          subErrored = true
          idx = args.length
      else if posIdx >= posSpecs.length then report(s"unexpected argument '$token'")
      else
        val spec = posSpecs(posIdx)
        spec.mode match
          case Mode.Repeated(_, _, _) =>
            offerValue(spec, token, spec.display)
          case Mode.Product(parser, _) =>
            offerProduct(spec, parser, spec.display, token)
            posIdx += 1
          case Mode.Single(parser, _) =>
            mention(spec)
            parser.readInto(token, values, spec.index) match
              case Result.Err(msg) => report(s"invalid value for '${spec.display}': $msg")
              case _               => ()
            posIdx += 1
          case Mode.Flag(_, _) =>
            offerValue(spec, token, spec.display)
            posIdx += 1

    private def isFreeToken(token: String): Boolean =
      token == "-" || !token.startsWith("-") ||
        (isNegativeNumber(token) && shortSpec(token(1)) == null)

    private update def consumeGreedy(spec: OptSpec): Unit = spec.mode match
      case Mode.Repeated(_, _, true) =>
        while idx < args.length && isFreeToken(args(idx)) do
          offerValue(spec, args(idx), spec.longDisplay)
          idx += 1
      case _ => ()

    /** Route tokens. Help/version are returned for the engine's lexical task to raise. */
    private update def route(): ParseError =
      while idx < args.length do
        val token = args(idx)
        idx += 1
        val free = noMoreOpts || isFreeToken(token)
        if free then handleFree(token)
        else if token == "--" then
          command.trailing match
            case Some(spec) =>
              spec.buildInto(args.drop(idx), values, spec.index) match
                case Result.Err(msg) => report(s"invalid arguments after '--': $msg")
                case _               => trailSeen = true
              idx = args.length
            case None => noMoreOpts = true
        else if token.startsWith("--") then
          val eq          = token.indexOf('=')
          val key         = if eq == -1 then token else token.substring(0, eq)
          val inlineValue = if eq == -1 then null else token.substring(eq + 1)
          if key == "--help" then
            return ParseError.Help(HelpFmt.render(command, prog, path, showHidden = false))
          val spec = command.longLookup.get(key)
          if spec == null then
            if key == "--version" && command.version.nonEmpty then
              return ParseError.Help(command.version.get())
            else if key == "--help-all" then
              return ParseError.Help(HelpFmt.render(command, prog, path, showHidden = true))
            else if defaultSubCase != null then selectSub(defaultSubCase, idx - 1)
            else
              val candidates = Vector.newBuilder[String]
              command.opts.foreach { option =>
                if !option.hidden then
                  candidates += option.long
                  candidates ++= option.aliases
              }
              candidates += "help" += "help-all"
              if command.version.nonEmpty then candidates += "version"
              val suggestion = Runtime
                .suggest(key.drop(2), candidates.result())
                .map(s => s" (did you mean '--$s'?)")
                .getOrElse("")
              report(s"unknown option '$key'$suggestion")
          else if inlineValue != null then offerValue(spec, inlineValue, key)
          else if isFlag(spec) then offerBare(spec)
          else
            spec.mode match
              case Mode.Product(parser, _) =>
                offerProduct(spec, parser, key, null)
              case _ =>
                val value = takeValue(key)
                if value != null then
                  offerValue(spec, value, key)
                  consumeGreedy(spec)
        else
          var i    = 1
          var stop = false
          while i < token.length && !stop do
            val c    = token(i)
            val spec = shortSpec(c)
            if spec == null then
              if c == 'h' then
                return ParseError.Help(HelpFmt.render(command, prog, path, showHidden = false))
              if i == 1 && defaultSubCase != null then selectSub(defaultSubCase, idx - 1)
              else report(s"unknown option '-$c'")
              stop = true
            else if isFlag(spec) then
              offerBare(spec)
              i += 1
            else
              spec.mode match
                case Mode.Product(parser, _) if i + 1 >= token.length =>
                  offerProduct(spec, parser, spec.shortDisplay, null)
                case _ =>
                  val attached = i + 1 < token.length
                  val raw      =
                    if attached then
                      if token(i + 1) == '=' then token.substring(i + 2)
                      else token.substring(i + 1)
                    else takeValue(spec.shortDisplay)
                  if raw != null then
                    offerValue(spec, raw, spec.shortDisplay)
                    if !attached then consumeGreedy(spec)
              stop = true
      null

    private update def reportInvalid(result: Result[Unit, String], display: String): Unit =
      result match
        case Result.Err(msg) => report(s"invalid value for '$display': $msg")
        case _               => ()

    private update def finishFlag(
        spec: SlotSpec,
        parser: flagged.Parser.Flag[?],
        display: String
    ): Unit =
      parser.countInto(flagCounts(spec.index), values, spec.index) match
        case Result.Err(msg) => report(s"flag '$display': $msg")
        case _               => ()

    /** False means a required slot is missing. */
    private update def finishSlot(spec: SlotSpec): Boolean =
      val index   = spec.index
      val display = spec.display
      spec.mode match
        case Mode.Flag(parser, optional) =>
          if !isSeen(index) then
            spec.default match
              case Some(_)          => ()
              case None if optional => values(index) = None
              case None             => reportInvalid(parser.countInto(0, values, index), display)
          else
            parser match
              case valued: flagged.Parser.ValuedFlag[?] if staged(spec) != null =>
                reportInvalid(
                  valued.readInto(staged(spec), values, index),
                  lastDisp(index)
                )
              case _ => finishFlag(spec, parser, display)
            if optional then values(index) = Some(values(index))
          true
        case Mode.Single(parser, optional) =>
          if !isSeen(index) then
            spec.default match
              case Some(_)          => ()
              case None if optional => values(index) = None
              case None             => return false
          else
            val raw = staged(spec)
            if raw != null then reportInvalid(parser.readInto(raw, values, index), lastDisp(index))
            if optional then values(index) = Some(values(index))
          true
        case Mode.Product(_, optional) =>
          if !isSeen(index) then
            spec.default match
              case Some(_)          => ()
              case None if optional => values(index) = None
              case None             => return false
          else if optional then values(index) = Some(values(index))
          true
        case Mode.Repeated(parser, _, _) =>
          if !isSeen(index) then
            spec.default match
              case Some(_) => ()
              case None    =>
                reportInvalid(parser.collector().finishInto(values, index), display)
          else if !reps(index).failed then
            reportInvalid(reps(index).finishInto(values, index), display)
          true

    private def markAbsent(
        splices: Array[Splice]^{},
        base: Int,
        initial: mutable.BitSet
    ): mutable.BitSet =
      var absent = initial
      var i      = 0
      while i < splices.length do
        val splice = splices(i)
        if splice.skipped(this, base) then
          if absent == null then absent = mutable.BitSet.empty
          absent.add(base + splice.slot)
          absent.addAll((base + splice.offset) until (base + splice.offset + splice.command.arity))
        else absent = markAbsent(splice.command.splices, base + splice.offset, absent)
        i += 1
      absent

    private update def validate(): ParseError =
      val absent =
        if command.splices.isEmpty then null else markAbsent(command.splices, 0, null)
      skipIdx = if absent == null then Set.empty else absent

      var missing: mutable.ArrayBuffer[String] = null
      var i                                    = 0
      while i < command.opts.length do
        val spec = command.opts(i)
        if !skipIdx(spec.index) && !finishSlot(spec) then
          if missing == null then missing = mutable.ArrayBuffer.empty[String]
          missing += spec.display
        i += 1
      i = 0
      while i < command.positionals.length do
        val spec = command.positionals(i)
        if !skipIdx(spec.index) && !finishSlot(spec) then
          if missing == null then missing = mutable.ArrayBuffer.empty[String]
          missing += spec.display
        i += 1
      if missing != null then
        val what = if missing.sizeIs == 1 then "argument" else "arguments"
        report(s"missing required $what: ${missing.mkString(", ")}")

      command.trailing match
        case Some(spec) =>
          if trailSeen then
            if spec.optional then values(spec.index) = Some(values(spec.index))
          else
            spec.default match
              case Some(_) => ()
              case None    =>
                if spec.optional then values(spec.index) = None
                else
                  spec.buildInto(Vector.empty, values, spec.index) match
                    case Result.Err(msg) => report(s"missing arguments after '--': $msg")
                    case _               => ()
        case None => ()

      if subGroup != null && selectedSub == null && !subErrored then
        subGroup.default match
          case Some(_) => ()
          case None    =>
            if defaultSubCase != null then selectSub(defaultSubCase, args.length)
            else if !subGroup.optional then
              report(
                s"missing command (expected one of: ${subGroup.cases.iterator.map(_.name).mkString(", ")})"
              )

      if errors == null then null else failure

    update def routeAndValidate(): ParseError =
      val terminal = route()
      if terminal != null then terminal else validate()

    /** `base`-relative destination slots of skipped splices, for [[Command.finishInto]]. */
    def skips: collection.Set[Int] = skipIdx

    /** Safe only after the full selected command chain has validated. */
    update def materializeDefaults(): Unit =
      if subGroup != null && selectedSub == null then
        values(subGroup.index) = subGroup.default match
          case Some(default) => default()
          case None          => None

      var i = 0
      while i < command.opts.length do
        val spec = command.opts(i)
        if spec.default.nonEmpty && !isSeen(spec.index) && !skipIdx(spec.index) then
          values(spec.index) = spec.default.get()
        i += 1
      i = 0
      while i < command.positionals.length do
        val spec = command.positionals(i)
        if spec.default.nonEmpty && !isSeen(spec.index) && !skipIdx(spec.index) then
          values(spec.index) = spec.default.get()
        i += 1

      command.trailing match
        case Some(spec) if !trailSeen =>
          spec.default match
            case Some(default) => values(spec.index) = default()
            case None          => ()
        case _ => ()
