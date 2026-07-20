package flagged.internal

import flagged.{ParseError, ParseResult}
import steps.result.Result
import steps.result.Result.{Ok, Err, eval}
import steps.result.Result.eval.ok
import scala.collection.immutable.ArraySeq
import scala.collection.mutable

/** The token-stream parser, in two orthogonal phases:
  *
  *   1. *routing* — walk the tokens, decide which spec each one belongs to and whether it consumes
  *      a value, and record per-slot scalars: a mention count, the last raw value and the spelling
  *      it arrived under, and whether the last mention carried a value (repeated specs parse their
  *      elements eagerly into growable buffers instead; subcommands and `--` trailing divert the
  *      remaining tokens immediately);
  *   1. *finishing* — one pass per spec interprets those scalars (count flags, last-wins singles,
  *      combined repeats), parsing single values exactly once — an overridden earlier mention is
  *      never parsed — and falling back to defaults, then builds the command's value.
  *
  * The hot path allocates no per-token objects: parsers write successful values straight into the
  * value slots through the `*Into` protocol (success is the shared `Result.done`), and the per-slot
  * state is primitive arrays.
  *
  * Errors do not stop parsing: routing and finishing both record every problem they find (unknown
  * options, missing values, invalid values, missing required arguments), and the parse fails at the
  * end with all of them. Only `--help` and delegation to a subcommand short-circuit.
  */
private[flagged] object Engine:

  def run(cmd: Command, prog: String, path: List[String], args: List[String]): ParseResult[Any] =
    Result:
      val full                      = (prog :: path).mkString(" ")
      def hint                      = s"Try '$full --help' for more information."
      val errors                    = mutable.ListBuffer.empty[String]
      def report(msg: String): Unit = errors += msg
      def helpNow(): Nothing        = eval.raise(ParseError.Help(HelpFmt.render(cmd, prog, path)))

      val n         = cmd.arity
      val values    = new Array[Any](n)
      val counts    = new Array[Int](n)
      val lastRaw   = new Array[String](n)
      val lastDisp  = new Array[String](n)  // null for positionals: reconstructed at finish
      val lastIsVal = new Array[Boolean](n) // for pure flags: value-mention already reported

      // element buffers for repeated specs, allocated only when one occurs
      var repBufs: Array[Array[Any]] = null
      var repLens: Array[Int]        = null

      var subValue   = Option.empty[Any]
      var subErrored = false
      var trailValue = Option.empty[Any]
      var rest       = args
      var posIdx     = 0
      var noMoreOpts = false

      def longOf(nm: String) = cmd.longIndex.get(nm)
      def shortOf(c: Char)   = cmd.shortIndex.get(c)

      def isNegativeNumber(s: String): Boolean =
        s.length > 1 && s(0) == '-' &&
          (s(1).isDigit || (s(1) == '.' && s.length > 2 && s(2).isDigit))

      def looksLikeOption(s: String): Boolean =
        s.length > 1 && s.startsWith("-") && !isNegativeNumber(s)

      /** `None` after reporting when the value is missing. */
      def takeValue(display: String): Option[String] =
        rest match
          case v :: tail if !looksLikeOption(v) =>
            rest = tail
            Some(v)
          case _ =>
            report(s"option '$display' requires a value")
            None

      def isFlag(spec: OptSpec): Boolean = spec.mode match
        case Mode.Flag(_, _) => true
        case _               => false

      /** Repeated elements parse eagerly: every element counts, none is overridden. `display` is
        * null for positionals and resolved only when an element fails.
        */
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
          val grown = new Array[Any](buf.length * 2)
          System.arraycopy(buf, 0, grown, 0, buf.length)
          buf = grown
          repBufs(index) = buf
        parser.parseElemInto(raw, buf, repLens(index)) match
          case Err(msg) =>
            val disp = if display == null then posName(index) else display
            report(s"invalid value for '$disp': $msg")
          case _ => repLens(index) += 1

      /** A value mention. `display` is null for positionals (rebuilt from the spec at finish). */
      def offerValue(index: Int, mode: Mode, raw: String, display: String): Unit =
        counts(index) += 1
        mode match
          case Mode.Repeated(p) =>
            addElem(index, p, raw, display)
          case Mode.Flag(p, _) if !p.isInstanceOf[flagged.Parser.ValuedFlag[?]] =>
            // a pure flag rejects an explicit value wherever it appears (report once)
            if !lastIsVal(index) then
              report(s"flag '$display' does not take a value")
              lastIsVal(index) = true
          case _ =>
            lastRaw(index) = raw
            lastDisp(index) = display
            lastIsVal(index) = true

      def offerBare(index: Int): Unit =
        counts(index) += 1
        lastIsVal(index) = false

      def posName(index: Int): String =
        s"<${cmd.positionals.find(_.index == index).fold("value")(_.name)}>"

      def handleFree(tok: String): Unit =
        cmd.sub match
          case Some(g) =>
            g.cases.find(c => c.name == tok || c.aliases.contains(tok)) match
              case Some(sc) => runSub(sc, rest)
              case None     =>
                defaultSub match
                  case Some(dc) => runSub(dc, tok :: rest)
                  case None     =>
                    val sug = Runtime
                      .suggest(tok, g.cases.filterNot(_.hidden).flatMap(c => c.name :: c.aliases))
                      .map(s => s" (did you mean '$s'?)")
                      .getOrElse("")
                    report(s"unknown command '$tok'$sug")
                    subErrored = true
                    rest = Nil // the remaining tokens belong to the unknown command
          case None =>
            if posIdx >= cmd.positionals.length then report(s"unexpected argument '$tok'")
            else
              val p = cmd.positionals(posIdx)
              offerValue(p.index, p.mode, tok, null)
              p.mode match
                case Mode.Repeated(_) => () // keep filling the last positional
                case _                => posIdx += 1

      /** With a `@default` command and no own options, unrecognized tokens are its arguments. */
      def defaultSub: Option[SubCase] =
        if cmd.opts.isEmpty then cmd.sub.flatMap(_.defaultCase) else None

      def runSub(sc: SubCase, args: List[String]): Unit =
        // .ok propagates the subcommand's Help/Failure to our caller unchanged
        subValue = Some(run(sc.command, prog, path :+ sc.name, args).ok)
        rest = Nil

      // ---- phase 1: routing ---------------------------------------------------

      while rest.nonEmpty do
        val tok = rest.head
        rest = rest.tail
        // `-2` is a positional value unless a short option `-2` is actually defined
        val isFree =
          noMoreOpts || tok == "-" || !tok.startsWith("-") ||
            (isNegativeNumber(tok) && shortOf(tok(1)).isEmpty)
        if isFree then handleFree(tok)
        else if tok == "--" then
          cmd.trailing match
            case Some(t) =>
              // divert everything after `--` to the trailing field, verbatim
              t.build(rest) match
                case Ok(v)    => trailValue = Some(v)
                case Err(msg) => report(s"invalid arguments after '--': $msg")
              rest = Nil
            case None => noMoreOpts = true
        else if tok.startsWith("--") then
          val body              = tok.drop(2)
          val (nm, inlineValue) = body.indexOf('=') match
            case -1 => (body, None)
            case i  => (body.take(i), Some(body.drop(i + 1)))
          if nm == "help" then helpNow()
          longOf(nm) match
            case None if nm == "version" && cmd.version.nonEmpty =>
              // a user option named `version` takes precedence (longOf matched above)
              eval.raise(ParseError.Help(cmd.version.get))
            case None if defaultSub.nonEmpty =>
              runSub(defaultSub.get, tok :: rest)
            case None =>
              val sug = Runtime
                .suggest(
                  nm,
                  cmd.opts.filterNot(_.hidden).flatMap(o => o.long :: o.aliases) :+ "help"
                )
                .map(s => s" (did you mean '--$s'?)")
                .getOrElse("")
              report(s"unknown option '--$nm'$sug")
            case Some(spec) =>
              inlineValue match
                case Some(v)              => offerValue(spec.index, spec.mode, v, s"--$nm")
                case None if isFlag(spec) => offerBare(spec.index)
                case None                 =>
                  takeValue(s"--$nm").foreach(v => offerValue(spec.index, spec.mode, v, s"--$nm"))
        else
          // short option cluster: -v, -abc, -o value, -ovalue, -o=value
          var i    = 1
          var stop = false
          while i < tok.length && !stop do
            val c = tok(i)
            if c == 'h' && shortOf('h').isEmpty then helpNow()
            shortOf(c) match
              case None if i == 1 && defaultSub.nonEmpty =>
                runSub(defaultSub.get, tok :: rest)
                stop = true
              case None =>
                report(s"unknown option '-$c'")
                stop = true // the rest of the cluster is unintelligible
              case Some(spec) if isFlag(spec) =>
                offerBare(spec.index)
                i += 1
              case Some(spec) =>
                val attached = tok.drop(i + 1)
                val raw      =
                  if attached.nonEmpty then
                    Some(if attached.startsWith("=") then attached.drop(1) else attached)
                  else takeValue(s"-$c")
                raw.foreach(v => offerValue(spec.index, spec.mode, v, s"-$c"))
                stop = true

      // ---- phase 2: finishing ---------------------------------------------------

      /** Interpret one spec's scalars; false means a required value is missing. */
      def finishSlot(index: Int, display: String, mode: Mode, default: Option[() => Any]): Boolean =
        mode match
          case Mode.Flag(parser, optional) =>
            if counts(index) == 0 then
              default match
                case Some(d)          => values(index) = d()
                case None if optional => values(index) = None
                case None             =>
                  parser.countInto(0, values, index) match
                    case Err(msg) => report(s"invalid value for '$display': $msg")
                    case _        => ()
            else
              if lastIsVal(index) then
                parser match
                  case vf: flagged.Parser.ValuedFlag[?] =>
                    vf.fromValueInto(lastRaw(index), values, index) match
                      case Err(msg) => report(s"invalid value for '${lastDisp(index)}': $msg")
                      case _        => ()
                  case _ => () // pure flag: the value mention was reported during routing
              else
                parser.countInto(counts(index), values, index) match
                  case Err(msg) => report(s"flag '$display': $msg")
                  case _        => ()
              if optional then values(index) = Some(values(index))
            true
          case Mode.Single(parser, optional) =>
            if counts(index) == 0 then
              default match
                case Some(d)          => values(index) = d()
                case None if optional => values(index) = None
                case None             => return false // missing required
            else
              val disp = if lastDisp(index) == null then display else lastDisp(index)
              parser.readInto(lastRaw(index), values, index) match
                case Err(msg) => report(s"invalid value for '$disp': $msg")
                case _        => ()
              if optional then values(index) = Some(values(index))
            true
          case Mode.Repeated(parser) =>
            val len = if repLens == null then 0 else repLens(index)
            if counts(index) == 0 then
              default match
                case Some(d) => values(index) = d()
                case None    =>
                  parser.buildErased(ArraySeq.empty[Any]) match
                    case Ok(v)    => values(index) = v
                    case Err(msg) => report(s"invalid value for '$display': $msg")
            else if len == counts(index) then // no element failed (failures were reported)
              val buf   = repBufs(index)
              val exact =
                if len == buf.length then buf
                else
                  val out = new Array[Any](len)
                  System.arraycopy(buf, 0, out, 0, len)
                  out
              parser.buildErased(ArraySeq.unsafeWrapArray(exact)) match
                case Ok(v)    => values(index) = v
                case Err(msg) => report(s"invalid value for '$display': $msg")
            true

      val missing = mutable.ListBuffer.empty[String]

      // slots of optional splices none of whose options occurred: the group parses to None, so
      // its required options are not enforced (nested optional splices recurse)
      def absentRanges(splices: List[Splice], base: Int): List[Range] =
        splices.flatMap { s =>
          val range = (base + s.offset) until (base + s.offset + s.command.arity)
          if s.optional && !range.exists(i => counts(i) > 0) then List(range)
          else absentRanges(s.command.splices, base + s.offset)
        }
      val skipIdx = absentRanges(cmd.splices, 0).flatten.toSet

      cmd.opts.foreach { o =>
        if !skipIdx(o.index) then
          if !finishSlot(o.index, s"--${o.long}", o.mode, o.default) then missing += s"--${o.long}"
      }
      cmd.positionals.foreach { p =>
        if !finishSlot(p.index, s"<${p.name}>", p.mode, p.default) then missing += s"<${p.name}>"
      }
      if missing.nonEmpty then
        val what = if missing.sizeIs == 1 then "argument" else "arguments"
        report(s"missing required $what: ${missing.mkString(", ")}")

      cmd.trailing.foreach { t =>
        values(t.index) = trailValue match
          case Some(v) => if t.optional then Some(v) else v
          case None    =>
            t.default.map(_()).getOrElse {
              if t.optional then None
              else
                t.build(Nil) match
                  case Ok(v)    => v
                  case Err(msg) =>
                    report(s"missing arguments after '--': $msg")
                    null
            }
      }

      cmd.sub.foreach { g =>
        values(g.index) = subValue match
          case Some(v) => if g.optional then Some(v) else v
          case None    =>
            g.default.map(_()).getOrElse {
              g.defaultCase match
                case Some(dc) =>
                  val v = run(dc.command, prog, path :+ dc.name, Nil).ok
                  if g.optional then Some(v) else v
                case None =>
                  if g.optional then None
                  else
                    if !subErrored then
                      report(
                        s"missing command (expected one of: ${g.cases.map(_.name).mkString(", ")})"
                      )
                    null
            }
      }

      if errors.nonEmpty then eval.raise(ParseError.Failure(errors.mkString("\n"), hint))

      cmd.finish(values, i => counts(i) > 0) match
        case Ok(v)    => v
        case Err(msg) => eval.raise(ParseError.Failure(msg, hint))
