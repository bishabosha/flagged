package flagged.internal

import flagged.{ParseError, ParseResult}
import steps.result.Result
import steps.result.Result.{Ok, Err, eval}
import steps.result.Result.eval.ok
import scala.collection.immutable.ArraySeq
import scala.collection.mutable

/** One occurrence of an option or positional on the command line. */
private enum Occ:
  /** A flag mention without a value (`-v`, `--verbose`). */
  case Bare

  /** A raw value, with the spelling it arrived under (for error messages). */
  case Val(raw: String, display: String)

/** The token-stream parser, in two orthogonal phases:
  *
  *   1. *routing* — walk the tokens, decide only which spec each one belongs to and whether it
  *      consumes a value, and record raw [[Occ]]urrences per value slot (subcommands and `--`
  *      trailing divert the remaining tokens immediately);
  *   1. *finishing* — one uniform pass interprets each spec's occurrence list according to its
  *      `Mode` (count flags, last-wins singles, combined repeats), falling back to defaults, then
  *      builds the command's value.
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

      val values     = new Array[Any](cmd.arity)
      val occs       = Array.fill(cmd.arity)(mutable.ListBuffer.empty[Occ])
      var subValue   = Option.empty[Any]
      var subErrored = false
      var trailValue = Option.empty[Any]
      var rest       = args
      var posIdx     = 0
      var noMoreOpts = false

      def longOf(n: String) = cmd.longIndex.get(n)
      def shortOf(c: Char)  = cmd.shortIndex.get(c)

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

      /** With a `@default` command and no own options, unrecognized tokens are its arguments. */
      def defaultSub: Option[SubCase] =
        if cmd.opts.isEmpty then cmd.sub.flatMap(_.defaultCase) else None

      def runSub(sc: SubCase, args: List[String]): Unit =
        // .ok propagates the subcommand's Help/Failure to our caller unchanged
        subValue = Some(run(sc.command, prog, path :+ sc.name, args).ok)
        rest = Nil

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
              occs(p.index) += Occ.Val(tok, s"<${p.name}>")
              p.mode match
                case Mode.Repeated(_) => () // keep filling the last positional
                case _                => posIdx += 1

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
                case Some(v)              => occs(spec.index) += Occ.Val(v, s"--$nm")
                case None if isFlag(spec) => occs(spec.index) += Occ.Bare
                case None                 =>
                  takeValue(s"--$nm").foreach(v => occs(spec.index) += Occ.Val(v, s"--$nm"))
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
                occs(spec.index) += Occ.Bare
                i += 1
              case Some(spec) =>
                val attached = tok.drop(i + 1)
                val raw      =
                  if attached.nonEmpty then
                    Some(if attached.startsWith("=") then attached.drop(1) else attached)
                  else takeValue(s"-$c")
                raw.foreach(v => occs(spec.index) += Occ.Val(v, s"-$c"))
                stop = true

      // ---- phase 2: finishing ---------------------------------------------------

      /** Report an invalid value; the placeholder result is never built into a value because a
        * reported error fails the parse before `finish`.
        */
      def orReport[A](display: String)(r: Result[A, String]): Any = r match
        case Ok(v)    => v
        case Err(msg) =>
          report(s"invalid value for '$display': $msg")
          null

      /** Interpret one spec's occurrences; `None` means a required value is missing. */
      def finishSpec(
          display: String,
          mode: Mode,
          default: Option[() => Any],
          occurrences: List[Occ]
      ): Option[Any] =
        mode match
          case Mode.Flag(parser, optional) =>
            def wrap(v: Any): Any     = if optional then Some(v) else v
            def fromBare: Option[Any] =
              parser.fromCount(occurrences.length) match
                case Ok(v)    => Some(wrap(v))
                case Err(msg) =>
                  report(s"flag '$display': $msg")
                  Some(null)
            def absent: Option[Any] =
              if optional then Some(default.map(_()).getOrElse(None))
              else Some(default.map(_()).getOrElse(orReport(display)(parser.fromCount(0))))
            parser match
              case vf: flagged.Parser.ValuedFlag[?] =>
                // the last mention wins, bare or valued
                occurrences.lastOption match
                  case Some(Occ.Val(raw, disp)) => Some(wrap(orReport(disp)(vf.fromValue(raw))))
                  case Some(Occ.Bare)           => fromBare
                  case None                     => absent
              case _ =>
                // a pure flag rejects an explicit value wherever it appears
                occurrences.collectFirst { case v: Occ.Val => v } match
                  case Some(v) =>
                    report(s"flag '${v.display}' does not take a value")
                    Some(null)
                  case None if occurrences.nonEmpty => fromBare
                  case None                         => absent
          case Mode.Single(parser, optional) =>
            occurrences.lastOption match
              case Some(Occ.Val(raw, disp)) =>
                val v = orReport(disp)(parser.asInstanceOf[flagged.Parser[Any]].read(raw))
                Some(if optional then Some(v) else v)
              case _ =>
                default.map(d => Some(d())).getOrElse(if optional then Some(None) else None)
          case Mode.Repeated(parser) =>
            var bad = false
            val arr = new Array[Any](occurrences.length)
            var k   = 0
            occurrences.foreach {
              case Occ.Val(raw, disp) =>
                parser.parseElem(raw) match
                  case Ok(v) =>
                    arr(k) = v
                    k += 1
                  case Err(msg) =>
                    report(s"invalid value for '$disp': $msg")
                    bad = true
              case Occ.Bare => ()
            }
            if bad then Some(null) // reported: the parse fails before anything is built
            else if k > 0 then
              val vals = ArraySeq.unsafeWrapArray(if k == arr.length then arr else arr.take(k))
              Some(orReport(display)(parser.buildErased(vals)))
            else
              default
                .map(d => Some(d()))
                .getOrElse(Some(orReport(display)(parser.buildErased(ArraySeq.empty[Any]))))

      val missing = mutable.ListBuffer.empty[String]

      // slots of optional splices none of whose options occurred: the group parses to None, so
      // its required options are not enforced (nested optional splices recurse)
      def absentRanges(splices: List[Splice], base: Int): List[Range] =
        splices.flatMap { s =>
          val range = (base + s.offset) until (base + s.offset + s.command.arity)
          if s.optional && !range.exists(i => occs(i).nonEmpty) then List(range)
          else absentRanges(s.command.splices, base + s.offset)
        }
      val skipIdx = absentRanges(cmd.splices, 0).flatten.toSet

      cmd.opts.foreach { o =>
        if !skipIdx(o.index) then
          finishSpec(s"--${o.long}", o.mode, o.default, occs(o.index).toList) match
            case Some(v) => values(o.index) = v
            case None    => missing += s"--${o.long}"
      }
      cmd.positionals.foreach { p =>
        finishSpec(s"<${p.name}>", p.mode, p.default, occs(p.index).toList) match
          case Some(v) => values(p.index) = v
          case None    => missing += s"<${p.name}>"
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

      cmd.finish(values, i => occs(i).nonEmpty) match
        case Ok(v)    => v
        case Err(msg) => eval.raise(ParseError.Failure(msg, hint))
