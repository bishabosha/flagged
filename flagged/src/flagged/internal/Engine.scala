package flagged.internal

import flagged.{ParseError, ParseResult}
import steps.result.Result
import steps.result.Result.{Err, eval}
import steps.result.Result.eval.{check, ok}
import scala.collection.mutable

/** The token-stream parser, in three orthogonal phases:
  *
  *   1. *routing* — a cursor walk over the argument array, deciding which spec each token belongs
  *      to and whether it consumes a value, and recording per-slot scalars: a mention count plus
  *      the last raw value and the spelling it arrived under (repeated specs parse their elements
  *      eagerly into per-slot collectors — collection builders for the built-in instances;
  *      subcommands and `--` trailing divert the remaining tokens immediately);
  *   1. *validation* — one pass per spec interprets those scalars (count flags, last-wins singles,
  *      combined repeats), parsing single values exactly once — an overridden earlier mention is
  *      never parsed — and collecting missing required arguments;
  *   1. *materialization* — after every selected command level validates, builds evaluate defaults,
  *      construct values, and finally invoke an `@run` method if one was selected.
  *
  * The hot path allocates nothing per token: parsers write successful values straight into the
  * value slots through the `*Into` protocol (success is the shared `Result.done`), per-slot state
  * is primitive arrays, lookups return null instead of `Option`, and displays are pre-existing
  * strings — the token itself for long options (the lookup keys carry the `--` prefix) and cached
  * spec fields otherwise. Strings are built, and error buffers exist, only when something is
  * reported.
  *
  * Errors do not stop parsing within one command level: routing and validation both record every
  * problem they find (unknown options, missing values, invalid values, missing required arguments),
  * and the parse fails at the end with all of them. A subcommand is not inspected after its parent
  * has already failed. Only `--help` short-circuits.
  */
private[flagged] object Engine:

  def run(
      cmd: Command,
      prog: String,
      path: IndexedSeq[String],
      args: IndexedSeq[String],
      from: Int
  ): ParseResult[Any] =
    val out = new Array[Any](1)
    runInto(cmd, prog, path, args, from, out, 0).map(_ => out(0))

  /** Parse one command level and write its result into `out`. The task boundary stays in this
    * method, so its `.ok`, `.check`, and `raise` operations cannot escape through a helper method
    * or closure.
    */
  private def runInto(
      cmd: Command,
      prog: String,
      path: IndexedSeq[String],
      args: IndexedSeq[String],
      from: Int,
      out: Array[Any],
      outIndex: Int
  ): ParseResult[Unit] =
    Result.task:
      def full = (prog +: path).mkString(" ")
      def hint = s"Try '$full --help' for more information."

      var errors: mutable.ArrayBuffer[String] = null
      def report(msg: String): Unit           =
        if errors == null then errors = mutable.ArrayBuffer.empty[String]
        errors += msg

      val n      = cmd.arity
      val values = new Array[Any](n)
      val counts = new Array[Int](n)

      // staging for named-option values (parsed once at finishing, so the last mention wins);
      // decided from the command's shape up front: a command without named options never stages,
      // since positionals parse eagerly (vals, not captured vars — those would box as ObjectRefs)
      val lastRaw: Array[String] = // per slot: null while only bare mentions have been seen
        if cmd.opts.isEmpty then null else new Array[String](n)
      val lastDisp: Array[String] = if cmd.opts.isEmpty then null else new Array[String](n)
      def stage(index: Int, raw: String, display: String): Unit =
        if lastRaw != null then
          lastRaw(index) = raw
          lastDisp(index) = display
      def staged(index: Int): String = if lastRaw == null then null else lastRaw(index)

      // element collectors for repeated specs, allocated only when one occurs
      var reps: Array[flagged.Parser.Collector] = null

      var selectedSub: SubCase = null
      var selectedFrom         = 0
      var subErrored           = false
      var trailSeen            = false
      var idx                  = from
      var posIdx               = 0
      var noMoreOpts           = false

      val shortChars = cmd.shortChars
      val shortSpecs = cmd.shortSpecs
      val posSpecs   = cmd.positionals
      val subGroup   = cmd.sub.orNull

      /** With a `@default` command, unrecognized tokens are forwarded as its arguments after this
        * command has had the chance to consume its own options.
        */
      val defaultSubCase: SubCase = cmd.sub.flatMap(_.defaultCase).orNull

      def shortSpec(c: Char): OptSpec =
        var i = 0
        while i < shortChars.length do
          if shortChars(i) == c then return shortSpecs(i)
          i += 1
        null

      def isNegativeNumber(s: String): Boolean =
        s.length > 1 && s(0) == '-' &&
          (s(1).isDigit || (s(1) == '.' && s.length > 2 && s(2).isDigit) || s == "-Infinity")

      def looksLikeOption(s: String): Boolean =
        s.length > 1 && s.startsWith("-") && !isNegativeNumber(s)

      /** Null after reporting when the value is missing. */
      def takeValue(display: String): String =
        if idx < args.length then
          val v = args(idx)
          if !looksLikeOption(v) then
            idx += 1
            return v
        report(s"option '$display' requires a value")
        null

      def isFlag(spec: OptSpec): Boolean = spec.mode match
        case Mode.Flag(_, _) => true
        case _               => false

      /** Repeated elements parse eagerly into the spec's collector: every element counts, none is
        * overridden. The value slot is scratch space until the collector's finish overwrites it.
        */
      def addElem(
          index: Int,
          parser: flagged.Parser.Repeated[?],
          raw: String,
          display: String
      ): Unit =
        if reps == null then reps = new Array[flagged.Parser.Collector](n)
        var c = reps(index)
        if c == null then
          c = parser.collector()
          reps(index) = c
        c.offer(raw, values, index) match
          case Err(msg) => report(s"invalid value for '$display': $msg")
          case _        => ()

      /** `@split`: every separator delimits a segment, each offered as one element (empty segments
        * included — the element parser decides).
        */
      def addSplitElems(
          index: Int,
          parser: flagged.Parser.Repeated[?],
          raw: String,
          display: String,
          sep: Char
      ): Unit =
        var start = 0
        while start <= raw.length do
          val cut = raw.indexOf(sep, start)
          val end = if cut == -1 then raw.length else cut
          addElem(index, parser, raw.substring(start, end), display)
          start = end + 1

      /** A product occurrence: consume the parser's arity in consecutive tokens (all of them, so a
        * bad element does not desync the stream) and build eagerly into the slot — a later
        * occurrence overwrites, like single values.
        */
      def offerProduct(
          index: Int,
          p: flagged.Parser.Product[?],
          display: String,
          first: String
      ): Unit =
        counts(index) += 1
        val ar      = p.arity
        val scratch = new Array[Any](ar)
        var failed  = false
        var k       = 0
        while k < ar do
          val tok = if k == 0 && first != null then first else takeValue(display)
          if tok == null then
            failed = true
            k = ar // missing value already reported; nothing left to consume
          else
            p.elements(k).readInto(tok, scratch, k) match
              case Err(msg) =>
                report(s"invalid value for '$display': $msg")
                failed = true
              case _ => ()
            k += 1
        if !failed then
          p.buildInto(scratch, values, index) match
            case Err(msg) => report(s"invalid value for '$display': $msg")
            case _        => ()

      /** A value mention. */
      def offerValue(index: Int, mode: Mode, raw: String, display: String): Unit =
        counts(index) += 1
        mode match
          case Mode.Repeated(p, sep, _) =>
            if sep != 0 then addSplitElems(index, p, raw, display, sep)
            else addElem(index, p, raw, display)
          case Mode.Product(p, _) =>
            // `--point=v` and attached short forms cannot carry a multi-token value
            report(s"option '$display' takes ${p.arity} values, each as its own argument")
          case Mode.Flag(p, _) if !p.takesValue =>
            // a pure flag rejects an explicit value wherever it appears; nothing is staged, so
            // finishing builds from the count alone (the parse has already failed)
            report(s"flag '$display' does not take a value")
          case _ =>
            stage(index, raw, display)

      def offerBare(index: Int): Unit =
        counts(index) += 1
        if lastRaw != null then lastRaw(index) = null // a later bare mention overrides a value

      def findCase(g: SubGroup, tok: String): SubCase =
        var i = 0
        while i < g.cases.length do
          val c = g.cases(i)
          if c.name == tok || c.aliases.contains(tok) then return c
          i += 1
        null

      def selectSub(sc: SubCase, fromIdx: Int): Unit =
        selectedSub = sc
        selectedFrom = fromIdx
        idx = args.length

      def handleFree(tok: String): Unit =
        if subGroup != null then
          val sc = findCase(subGroup, tok)
          if sc != null then selectSub(sc, idx)
          else if defaultSubCase != null then selectSub(defaultSubCase, idx - 1)
          else
            val cand = Vector.newBuilder[String]
            subGroup.cases.foreach { c =>
              if !c.hidden then
                cand += c.name
                cand ++= c.aliases
            }
            val sug = Runtime
              .suggest(tok, cand.result())
              .map(s => s" (did you mean '$s'?)")
              .getOrElse("")
            report(s"unknown command '$tok'$sug")
            subErrored = true
            idx = args.length // the remaining tokens belong to the unknown command
        else if posIdx >= posSpecs.length then report(s"unexpected argument '$tok'")
        else
          val p = posSpecs(posIdx)
          p.mode match
            case Mode.Repeated(_, _, _) =>
              offerValue(p.index, p.mode, tok, p.display) // keep filling the last positional
            case Mode.Product(pr, _) =>
              // this token starts the product; the remaining arity-1 tokens follow it
              offerProduct(p.index, pr, p.display, tok)
              posIdx += 1
            case Mode.Single(parser, _) =>
              // a single positional is never overridden: parse it now, nothing staged
              counts(p.index) += 1
              parser.readInto(tok, values, p.index) match
                case Err(msg) => report(s"invalid value for '${p.display}': $msg")
                case _        => ()
              posIdx += 1
            case Mode.Flag(_, _) => // positionals never resolve to Flag mode
              offerValue(p.index, p.mode, tok, p.display)
              posIdx += 1

      /** A token routed as a value rather than an option: `-`, non-dash, or a negative number with
        * no matching short option (`--` is never free).
        */
      def isFreeToken(tok: String): Boolean =
        tok == "-" || !tok.startsWith("-") ||
          (isNegativeNumber(tok) && shortSpec(tok(1)) == null)

      /** `@greedy`: keep consuming free tokens as further elements of the same option (assembly
        * guarantees no positionals or subcommands compete for them).
        */
      def consumeGreedy(spec: OptSpec): Unit = spec.mode match
        case Mode.Repeated(_, _, true) =>
          while idx < args.length && isFreeToken(args(idx)) do
            offerValue(spec.index, spec.mode, args(idx), spec.longDisplay)
            idx += 1
        case _ => ()

      // ---- phase 1: routing ---------------------------------------------------

      while idx < args.length do
        val tok = args(idx)
        idx += 1
        // `-2` is a positional value unless a short option `-2` is actually defined
        val isFree = noMoreOpts || isFreeToken(tok)
        if isFree then handleFree(tok)
        else if tok == "--" then
          cmd.trailing match
            case Some(t) =>
              // divert everything after `--` to the trailing field, verbatim — written straight
              // into the slot (wrapped for an Option field at finishing)
              t.buildInto(args.drop(idx), values, t.index) match
                case Err(msg) => report(s"invalid arguments after '--': $msg")
                case _        => trailSeen = true
              idx = args.length
            case None => noMoreOpts = true
        else if tok.startsWith("--") then
          // the lookup keys carry the `--` prefix: a plain long token needs no substring and
          // doubles as its own display spelling
          val eq          = tok.indexOf('=')
          val key         = if eq == -1 then tok else tok.substring(0, eq)
          val inlineValue = if eq == -1 then null else tok.substring(eq + 1)
          if key == "--help" then
            eval.raise(ParseError.Help(HelpFmt.render(cmd, prog, path, showHidden = false)))
          val spec = cmd.longLookup.get(key)
          if spec == null then
            if key == "--version" && cmd.version.nonEmpty then
              // a user option named `version` takes precedence (the lookup ran first)
              eval.raise(ParseError.Help(cmd.version.get()))
            else if key == "--help-all" then
              eval.raise(ParseError.Help(HelpFmt.render(cmd, prog, path, showHidden = true)))
            else if defaultSubCase != null then selectSub(defaultSubCase, idx - 1)
            else
              val cand = Vector.newBuilder[String]
              cmd.opts.foreach { o =>
                if !o.hidden then
                  cand += o.long
                  cand ++= o.aliases
              }
              cand += "help" += "help-all"
              if cmd.version.nonEmpty then cand += "version"
              val sug = Runtime
                .suggest(key.drop(2), cand.result())
                .map(s => s" (did you mean '--$s'?)")
                .getOrElse("")
              report(s"unknown option '$key'$sug")
          else if inlineValue != null then offerValue(spec.index, spec.mode, inlineValue, key)
          else if isFlag(spec) then offerBare(spec.index)
          else
            spec.mode match
              case Mode.Product(p, _) => offerProduct(spec.index, p, key, null)
              case _                  =>
                val v = takeValue(key)
                if v != null then
                  offerValue(spec.index, spec.mode, v, key)
                  consumeGreedy(spec)
        else
          // short option cluster: -v, -abc, -o value, -ovalue, -o=value
          var i    = 1
          var stop = false
          while i < tok.length && !stop do
            val c    = tok(i)
            val spec = shortSpec(c)
            if spec == null then
              if c == 'h' then
                eval.raise(ParseError.Help(HelpFmt.render(cmd, prog, path, showHidden = false)))
              if i == 1 && defaultSubCase != null then selectSub(defaultSubCase, idx - 1)
              else report(s"unknown option '-$c'")
              stop = true // the rest of the cluster is unintelligible
            else if isFlag(spec) then
              offerBare(spec.index)
              i += 1
            else
              spec.mode match
                case Mode.Product(p, _) if i + 1 >= tok.length =>
                  offerProduct(spec.index, p, spec.shortDisplay, null)
                case _ =>
                  // an attached value (`-p1`, `-p=1`) pins exactly one token: no greedy
                  // continuation, and a product spec rejects it in offerValue
                  val attached = i + 1 < tok.length
                  val raw      =
                    if attached then
                      if tok(i + 1) == '=' then tok.substring(i + 2) else tok.substring(i + 1)
                    else takeValue(spec.shortDisplay)
                  if raw != null then
                    offerValue(spec.index, spec.mode, raw, spec.shortDisplay)
                    if !attached then consumeGreedy(spec)
              stop = true

      // ---- phase 2: validation --------------------------------------------------

      def reportInvalid(r: Result[Unit, String], display: String): Unit = r match
        case Err(msg) => report(s"invalid value for '$display': $msg")
        case _        => ()

      def finishFlag(parser: flagged.Parser.Flag[?], index: Int, display: String): Unit =
        parser.countInto(counts(index), values, index) match
          case Err(msg) => report(s"flag '$display': $msg")
          case _        => ()

      /** Interpret one spec's scalars; false means a required value is missing. */
      def finishSlot(index: Int, display: String, mode: Mode, default: Option[() => Any]): Boolean =
        mode match
          case Mode.Flag(parser, optional) =>
            if counts(index) == 0 then
              default match
                case Some(_)          => () // materialized only after every command level validates
                case None if optional => values(index) = None
                case None             => reportInvalid(parser.countInto(0, values, index), display)
            else
              parser match
                case vf: flagged.Parser.ValuedFlag[?] if staged(index) != null =>
                  reportInvalid(vf.readInto(lastRaw(index), values, index), lastDisp(index))
                case _ => finishFlag(parser, index, display)
              if optional then values(index) = Some(values(index))
            true
          case Mode.Single(parser, optional) =>
            if counts(index) == 0 then
              default match
                case Some(_)          => () // materialized only after every command level validates
                case None if optional => values(index) = None
                case None => return false // missing required
            else
              // null staged raw: an eagerly parsed positional, already in its slot
              val raw = staged(index)
              if raw != null then
                reportInvalid(parser.readInto(raw, values, index), lastDisp(index))
              if optional then values(index) = Some(values(index))
            true
          case Mode.Product(_, optional) =>
            // occurrences were parsed and built eagerly at routing; only absence remains
            if counts(index) == 0 then
              default match
                case Some(_)          => () // materialized only after every command level validates
                case None if optional => values(index) = None
                case None => return false // missing required
            else if optional then values(index) = Some(values(index))
            true
          case Mode.Repeated(parser, _, _) =>
            if counts(index) == 0 then
              default match
                case Some(_) => () // materialized only after every command level validates
                case None    =>
                  // an empty collector: `build` still decides (it may require an occurrence)
                  reportInvalid(parser.collector().finishInto(values, index), display)
            else
              val c = reps(index)
              if !c.failed then // failed elements were reported when offered
                reportInvalid(c.finishInto(values, index), display)
            true

      var missing: mutable.ArrayBuffer[String] = null
      def addMissing(display: String): Unit    =
        if missing == null then missing = mutable.ArrayBuffer.empty[String]
        missing += display

      // slots of skipped splices (optional or defaulted, none of their options occurring): the
      // group falls back to None or its field default, so its required options are not enforced
      // (nested splices recurse). Accumulated directly — the common no-skip parse allocates
      // nothing and shares Set.empty
      def markAbsent(splices: IndexedSeq[Splice], base: Int, acc0: mutable.BitSet): mutable.BitSet =
        var acc = acc0
        var i   = 0
        while i < splices.length do
          val s = splices(i)
          if s.skipped(counts, base) then
            if acc == null then acc = mutable.BitSet.empty
            acc.addAll((base + s.offset) until (base + s.offset + s.command.arity))
          else acc = markAbsent(s.command.splices, base + s.offset, acc)
          i += 1
        acc
      val skipIdx: collection.Set[Int] =
        val absent = if cmd.splices.isEmpty then null else markAbsent(cmd.splices, 0, null)
        if absent == null then Set.empty else absent

      val slotSpecs = cmd.slots
      var si        = 0
      while si < slotSpecs.length do
        val spec = slotSpecs(si)
        if !skipIdx(spec.index) then
          if !finishSlot(spec.index, spec.display, spec.mode, spec.default) then
            addMissing(spec.display)
        si += 1
      if missing != null then
        val what = if missing.sizeIs == 1 then "argument" else "arguments"
        report(s"missing required $what: ${missing.mkString(", ")}")

      cmd.trailing match
        case Some(t) =>
          if trailSeen then
            if t.optional then values(t.index) = Some(values(t.index))
          else
            t.default match
              case Some(_) => () // materialized only after every command level validates
              case None    =>
                if t.optional then values(t.index) = None
                else
                  t.buildInto(Vector.empty, values, t.index) match
                    case Err(msg) => report(s"missing arguments after '--': $msg")
                    case _        => ()
        case None => ()

      cmd.sub match
        case Some(g) if selectedSub == null && !subErrored =>
          g.default match
            case Some(_) => ()
            case None    =>
              g.defaultCase match
                case Some(dc) => selectSub(dc, args.length)
                case None     =>
                  if !g.optional then
                    report(
                      s"missing command (expected one of: ${g.cases.iterator.map(_.name).mkString(", ")})"
                    )
        case None => ()
        case _    => ()

      if errors != null then eval.raise(ParseError.Failure(errors.mkString("\n"), hint))

      // ---- phase 3: materialization --------------------------------------------

      cmd.sub match
        case Some(g) =>
          values(g.index) =
            if selectedSub != null then
              runInto(
                selectedSub.command,
                prog,
                path :+ selectedSub.name,
                args,
                selectedFrom,
                values,
                g.index
              ).check
              val v = values(g.index)
              if g.optional then Some(v) else v
            else
              g.default match
                case Some(d) => d()
                case None    => None // only an absent optional group can reach this branch
        case None => ()

      val defaultSlots = cmd.defaultSlots
      var di           = 0
      while di < defaultSlots.length do
        val spec = defaultSlots(di)
        if counts(spec.index) == 0 && !skipIdx(spec.index) then
          values(spec.index) = spec.default.get()
        di += 1

      cmd.trailing.foreach { t =>
        if !trailSeen then t.default.foreach(d => values(t.index) = d())
      }

      cmd.finishInto(values, counts, 0, out, outIndex) match
        case Err(msg) => eval.raise(ParseError.Failure(msg, hint))
        case _        => ()
