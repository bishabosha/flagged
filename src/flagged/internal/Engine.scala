package flagged.internal

import flagged.{ParseError, ParseResult}
import steps.result.Result
import steps.result.Result.{Ok, Err, eval}
import steps.result.Result.eval.ok
import scala.collection.immutable.ArraySeq
import scala.collection.mutable

/** The token-stream parser, in two orthogonal phases:
  *
  *   1. *routing* — a cursor walk over the argument array, deciding which spec each token belongs
  *      to and whether it consumes a value, and recording per-slot scalars: a mention count plus
  *      the last raw value and the spelling it arrived under (repeated specs parse their elements
  *      eagerly into growable buffers instead; subcommands and `--` trailing divert the remaining
  *      tokens immediately);
  *   1. *finishing* — one pass per spec interprets those scalars (count flags, last-wins singles,
  *      combined repeats), parsing single values exactly once — an overridden earlier mention is
  *      never parsed — and falling back to defaults, then builds the command's value.
  *
  * The hot path allocates nothing per token: parsers write successful values straight into the
  * value slots through the `*Into` protocol (success is the shared `Result.done`), per-slot state
  * is primitive arrays, lookups return null instead of `Option`, and displays are pre-existing
  * strings — the token itself for long options (the lookup keys carry the `--` prefix) and cached
  * spec fields otherwise. Strings are built, and error buffers exist, only when something is
  * reported.
  *
  * Errors do not stop parsing: routing and finishing both record every problem they find (unknown
  * options, missing values, invalid values, missing required arguments), and the parse fails at the
  * end with all of them. Only `--help` and delegation to a subcommand short-circuit.
  */
private[flagged] object Engine:

  def run(
      cmd: Command,
      prog: String,
      path: List[String],
      args: IndexedSeq[String],
      from: Int
  ): ParseResult[Any] =
    Result:
      def full = (prog :: path).mkString(" ")
      def hint = s"Try '$full --help' for more information."

      var errors: mutable.ListBuffer[String] = null
      def report(msg: String): Unit          =
        if errors == null then errors = mutable.ListBuffer.empty[String]
        errors += msg
      def helpNow(): Nothing = eval.raise(ParseError.Help(HelpFmt.render(cmd, prog, path)))

      val n        = cmd.arity
      val values   = new Array[Any](n)
      val counts   = new Array[Int](n)
      val lastRaw  = new Array[String](n) // null while only bare mentions have been seen
      val lastDisp = new Array[String](n)

      // element buffers for repeated specs, allocated only when one occurs
      var repBufs: Array[Array[Any]] = null
      var repLens: Array[Int]        = null

      var subValue   = Option.empty[Any]
      var subErrored = false
      var trailValue = Option.empty[Any]
      var idx        = from
      var posIdx     = 0
      var noMoreOpts = false

      val shortChars = cmd.shortChars
      val shortSpecs = cmd.shortSpecs

      /** With a `@default` command and no own options, unrecognized tokens are its arguments. */
      val defaultSubCase: SubCase =
        if cmd.opts.isEmpty then cmd.sub.flatMap(_.defaultCase).orNull else null

      def shortSpec(c: Char): OptSpec =
        var i = 0
        while i < shortChars.length do
          if shortChars(i) == c then return shortSpecs(i)
          i += 1
        null

      def isNegativeNumber(s: String): Boolean =
        s.length > 1 && s(0) == '-' &&
          (s(1).isDigit || (s(1) == '.' && s.length > 2 && s(2).isDigit))

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

      def copyOf(src: Array[Any], newLength: Int): Array[Any] =
        val out = new Array[Any](newLength)
        System.arraycopy(src, 0, out, 0, math.min(src.length, newLength))
        out

      /** Repeated elements parse eagerly: every element counts, none is overridden. */
      def addElem(
          index: Int,
          parser: flagged.Parser.Repeated[?],
          raw: String,
          display: String
      ): Unit =
        if repBufs == null then
          repBufs = new Array[Array[Any]](n)
          repLens = new Array[Int](n)
        var buf = repBufs(index)
        if buf == null then
          buf = new Array[Any](4)
          repBufs(index) = buf
        else if repLens(index) == buf.length then
          buf = copyOf(buf, buf.length * 2)
          repBufs(index) = buf
        parser.parseElemInto(raw, buf, repLens(index)) match
          case Err(msg) => report(s"invalid value for '$display': $msg")
          case _        => repLens(index) += 1

      /** A value mention. */
      def offerValue(index: Int, mode: Mode, raw: String, display: String): Unit =
        counts(index) += 1
        mode match
          case Mode.Repeated(p) =>
            addElem(index, p, raw, display)
          case Mode.Flag(p, _) if !p.takesValue =>
            // a pure flag rejects an explicit value wherever it appears; lastRaw doubles as the
            // report-once latch (these specs never parse it)
            if lastRaw(index) == null then
              report(s"flag '$display' does not take a value")
              lastRaw(index) = raw
          case _ =>
            lastRaw(index) = raw
            lastDisp(index) = display

      def offerBare(index: Int): Unit =
        counts(index) += 1
        lastRaw(index) = null // a later bare mention overrides an earlier value

      def findCase(g: SubGroup, tok: String): SubCase =
        var i = 0
        while i < g.cases.length do
          val c = g.cases(i)
          if c.name == tok || c.aliases.contains(tok) then return c
          i += 1
        null

      def runSub(sc: SubCase, fromIdx: Int): Unit =
        // .ok propagates the subcommand's Help/Failure to our caller unchanged
        subValue = Some(run(sc.command, prog, path :+ sc.name, args, fromIdx).ok)
        idx = args.length

      def handleFree(tok: String): Unit =
        cmd.sub match
          case Some(g) =>
            val sc = findCase(g, tok)
            if sc != null then runSub(sc, idx)
            else if defaultSubCase != null then runSub(defaultSubCase, idx - 1)
            else
              val sug = Runtime
                .suggest(tok, g.cases.filterNot(_.hidden).flatMap(c => c.name :: c.aliases))
                .map(s => s" (did you mean '$s'?)")
                .getOrElse("")
              report(s"unknown command '$tok'$sug")
              subErrored = true
              idx = args.length // the remaining tokens belong to the unknown command
          case None =>
            if posIdx >= cmd.positionals.length then report(s"unexpected argument '$tok'")
            else
              val p = cmd.positionals(posIdx)
              offerValue(p.index, p.mode, tok, p.display)
              p.mode match
                case Mode.Repeated(_) => () // keep filling the last positional
                case _                => posIdx += 1

      // ---- phase 1: routing ---------------------------------------------------

      while idx < args.length do
        val tok = args(idx)
        idx += 1
        // `-2` is a positional value unless a short option `-2` is actually defined
        val isFree =
          noMoreOpts || tok == "-" || !tok.startsWith("-") ||
            (isNegativeNumber(tok) && shortSpec(tok(1)) == null)
        if isFree then handleFree(tok)
        else if tok == "--" then
          cmd.trailing match
            case Some(t) =>
              // divert everything after `--` to the trailing field, verbatim
              t.build(args.iterator.drop(idx).toList) match
                case Ok(v)    => trailValue = Some(v)
                case Err(msg) => report(s"invalid arguments after '--': $msg")
              idx = args.length
            case None => noMoreOpts = true
        else if tok.startsWith("--") then
          // the lookup keys carry the `--` prefix: a plain long token needs no substring and
          // doubles as its own display spelling
          val eq          = tok.indexOf('=')
          val key         = if eq == -1 then tok else tok.substring(0, eq)
          val inlineValue = if eq == -1 then null else tok.substring(eq + 1)
          if key == "--help" then helpNow()
          val spec = cmd.longLookup.get(key)
          if spec == null then
            if key == "--version" && cmd.version.nonEmpty then
              // a user option named `version` takes precedence (the lookup ran first)
              eval.raise(ParseError.Help(cmd.version.get))
            else if defaultSubCase != null then runSub(defaultSubCase, idx - 1)
            else
              val sug = Runtime
                .suggest(
                  key.drop(2),
                  cmd.opts.filterNot(_.hidden).flatMap(o => o.long :: o.aliases) :+ "help"
                )
                .map(s => s" (did you mean '--$s'?)")
                .getOrElse("")
              report(s"unknown option '$key'$sug")
          else if inlineValue != null then offerValue(spec.index, spec.mode, inlineValue, key)
          else if isFlag(spec) then offerBare(spec.index)
          else
            val v = takeValue(key)
            if v != null then offerValue(spec.index, spec.mode, v, key)
        else
          // short option cluster: -v, -abc, -o value, -ovalue, -o=value
          var i    = 1
          var stop = false
          while i < tok.length && !stop do
            val c    = tok(i)
            val spec = shortSpec(c)
            if spec == null then
              if c == 'h' then helpNow()
              if i == 1 && defaultSubCase != null then runSub(defaultSubCase, idx - 1)
              else report(s"unknown option '-$c'")
              stop = true // the rest of the cluster is unintelligible
            else if isFlag(spec) then
              offerBare(spec.index)
              i += 1
            else
              val raw =
                if i + 1 < tok.length then
                  if tok(i + 1) == '=' then tok.substring(i + 2) else tok.substring(i + 1)
                else takeValue(spec.shortDisplay)
              if raw != null then offerValue(spec.index, spec.mode, raw, spec.shortDisplay)
              stop = true

      // ---- phase 2: finishing ---------------------------------------------------

      def reportInvalid(r: Result[Unit, String], display: String): Unit = r match
        case Err(msg) => report(s"invalid value for '$display': $msg")
        case _        => ()

      def fromCount(parser: flagged.Parser.Flag[?], index: Int, display: String): Unit =
        parser.countInto(counts(index), values, index) match
          case Err(msg) => report(s"flag '$display': $msg")
          case _        => ()

      /** Interpret one spec's scalars; false means a required value is missing. */
      def finishSlot(index: Int, display: String, mode: Mode, default: Option[() => Any]): Boolean =
        mode match
          case Mode.Flag(parser, optional) =>
            if counts(index) == 0 then
              default match
                case Some(d)          => values(index) = d()
                case None if optional => values(index) = None
                case None             => reportInvalid(parser.countInto(0, values, index), display)
            else
              parser match
                case vf: flagged.Parser.ValuedFlag[?] if lastRaw(index) != null =>
                  reportInvalid(vf.fromValueInto(lastRaw(index), values, index), lastDisp(index))
                case _ =>
                  // bare mentions only — or a pure flag whose value mention (its lastRaw latch)
                  // was already reported during routing
                  if lastRaw(index) == null then fromCount(parser, index, display)
              if optional then values(index) = Some(values(index))
            true
          case Mode.Single(parser, optional) =>
            if counts(index) == 0 then
              default match
                case Some(d)          => values(index) = d()
                case None if optional => values(index) = None
                case None             => return false // missing required
            else
              reportInvalid(parser.readInto(lastRaw(index), values, index), lastDisp(index))
              if optional then values(index) = Some(values(index))
            true
          case Mode.Repeated(parser) =>
            val len = if repLens == null then 0 else repLens(index)
            if counts(index) == 0 then
              default match
                case Some(d) => values(index) = d()
                case None    =>
                  reportInvalid(parser.buildInto(ArraySeq.empty[Any], values, index), display)
            else if len == counts(index) then // no element failed (failures were reported)
              val buf   = repBufs(index)
              val exact = if len == buf.length then buf else copyOf(buf, len)
              reportInvalid(
                parser.buildInto(ArraySeq.unsafeWrapArray(exact), values, index),
                display
              )
            true

      var missing: mutable.ListBuffer[String] = null
      def addMissing(display: String): Unit   =
        if missing == null then missing = mutable.ListBuffer.empty[String]
        missing += display

      // slots of optional splices none of whose options occurred: the group parses to None, so
      // its required options are not enforced (nested optional splices recurse)
      def absentRanges(splices: List[Splice], base: Int): List[Range] =
        splices.flatMap { s =>
          if s.optional && !s.mentioned(counts, base) then
            List((base + s.offset) until (base + s.offset + s.command.arity))
          else absentRanges(s.command.splices, base + s.offset)
        }
      val skipIdx: Set[Int] =
        if cmd.splices.isEmpty then Set.empty else absentRanges(cmd.splices, 0).flatten.toSet

      var oi = 0
      while oi < cmd.opts.length do
        val o = cmd.opts(oi)
        if !skipIdx(o.index) then
          if !finishSlot(o.index, o.longDisplay, o.mode, o.default) then addMissing(o.longDisplay)
        oi += 1
      var pi = 0
      while pi < cmd.positionals.length do
        val p = cmd.positionals(pi)
        if !finishSlot(p.index, p.display, p.mode, p.default) then addMissing(p.display)
        pi += 1
      if missing != null then
        val what = if missing.sizeIs == 1 then "argument" else "arguments"
        report(s"missing required $what: ${missing.mkString(", ")}")

      cmd.trailing match
        case Some(t) =>
          values(t.index) = trailValue match
            case Some(v) => if t.optional then Some(v) else v
            case None    =>
              t.default match
                case Some(d) => d()
                case None    =>
                  if t.optional then None
                  else
                    t.build(Nil) match
                      case Ok(v)    => v
                      case Err(msg) =>
                        report(s"missing arguments after '--': $msg")
                        null
        case None => ()

      cmd.sub match
        case Some(g) =>
          values(g.index) = subValue match
            case Some(v) => if g.optional then Some(v) else v
            case None    =>
              g.default match
                case Some(d) => d()
                case None    =>
                  g.defaultCase match
                    case Some(dc) =>
                      val v = run(dc.command, prog, path :+ dc.name, args, args.length).ok
                      if g.optional then Some(v) else v
                    case None =>
                      if g.optional then None
                      else
                        if !subErrored then
                          report(
                            s"missing command (expected one of: ${g.cases.map(_.name).mkString(", ")})"
                          )
                        null
        case None => ()

      if errors != null then eval.raise(ParseError.Failure(errors.mkString("\n"), hint))

      cmd.finish(values, counts, 0) match
        case Ok(v)    => v
        case Err(msg) => eval.raise(ParseError.Failure(msg, hint))
