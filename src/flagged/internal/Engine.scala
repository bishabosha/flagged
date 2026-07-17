package flagged.internal

import flagged.{ParseError, ParseResult}
import steps.result.Result
import steps.result.Result.{Ok, Err, eval}
import steps.result.Result.eval.ok
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
  */
private[flagged] object Engine:

  def run(cmd: Command, prog: String, path: List[String], args: List[String]): ParseResult[Any] =
    Result:
      val full                       = (prog :: path).mkString(" ")
      def hint                       = s"Try '$full --help' for more information."
      def fail(msg: String): Nothing = eval.raise(ParseError.Failure(msg, hint))
      def helpNow(): Nothing         = eval.raise(ParseError.Help(HelpFmt.render(cmd, prog, path)))

      val values     = new Array[Any](cmd.arity)
      val occs       = Array.fill(cmd.arity)(mutable.ListBuffer.empty[Occ])
      var subValue   = Option.empty[Any]
      var trailValue = Option.empty[Any]
      var rest       = args
      var posIdx     = 0
      var noMoreOpts = false

      def longOf(n: String) = cmd.opts.find(_.long == n)
      def shortOf(c: Char)  = cmd.opts.find(_.short.contains(c))

      def isNegativeNumber(s: String): Boolean =
        s.length > 1 && s(0) == '-' &&
          (s(1).isDigit || (s(1) == '.' && s.length > 2 && s(2).isDigit))

      def looksLikeOption(s: String): Boolean =
        s.length > 1 && s.startsWith("-") && !isNegativeNumber(s)

      def takeValue(display: String): String =
        rest match
          case v :: tail if !looksLikeOption(v) =>
            rest = tail
            v
          case _ =>
            fail(s"option '$display' requires a value")

      def isFlag(spec: OptSpec): Boolean = spec.mode match
        case Mode.Flag(_, _) => true
        case _               => false

      def handleFree(tok: String): Unit =
        cmd.sub match
          case Some(g) =>
            g.cases.find(_.name == tok) match
              case Some(sc) =>
                // .ok propagates the subcommand's Help/Failure to our caller unchanged
                subValue = Some(run(sc.command, prog, path :+ sc.name, rest).ok)
                rest = Nil
              case None =>
                val sug = Runtime
                  .suggest(tok, g.cases.map(_.name))
                  .map(s => s" (did you mean '$s'?)")
                  .getOrElse("")
                fail(s"unknown command '$tok'$sug")
          case None =>
            if posIdx >= cmd.positionals.length then fail(s"unexpected argument '$tok'")
            val p = cmd.positionals(posIdx)
            occs(p.index) += Occ.Val(tok, s"<${p.name}>")
            p.mode match
              case Mode.Repeated(_, _) => () // keep filling the last positional
              case _                   => posIdx += 1

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
                case Err(msg) => fail(s"invalid arguments after '--': $msg")
              rest = Nil
            case None => noMoreOpts = true
        else if tok.startsWith("--") then
          val body              = tok.drop(2)
          val (nm, inlineValue) = body.indexOf('=') match
            case -1 => (body, None)
            case i  => (body.take(i), Some(body.drop(i + 1)))
          if nm == "help" then helpNow()
          longOf(nm) match
            case None =>
              val sug = Runtime
                .suggest(nm, cmd.opts.map(_.long) :+ "help")
                .map(s => s" (did you mean '--$s'?)")
                .getOrElse("")
              fail(s"unknown option '--$nm'$sug")
            case Some(spec) =>
              occs(spec.index) += {
                inlineValue match
                  case Some(v)              => Occ.Val(v, s"--$nm")
                  case None if isFlag(spec) => Occ.Bare
                  case None                 => Occ.Val(takeValue(s"--$nm"), s"--$nm")
              }
        else
          // short option cluster: -v, -abc, -o value, -ovalue, -o=value
          var i             = 1
          var consumedValue = false
          while i < tok.length && !consumedValue do
            val c = tok(i)
            if c == 'h' && shortOf('h').isEmpty then helpNow()
            shortOf(c) match
              case None =>
                fail(s"unknown option '-$c'")
              case Some(spec) if isFlag(spec) =>
                occs(spec.index) += Occ.Bare
                i += 1
              case Some(spec) =>
                val attached = tok.drop(i + 1)
                val raw      =
                  if attached.nonEmpty then
                    if attached.startsWith("=") then attached.drop(1) else attached
                  else takeValue(s"-$c")
                occs(spec.index) += Occ.Val(raw, s"-$c")
                consumedValue = true

      // ---- phase 2: finishing ---------------------------------------------------

      def orFail[A](display: String)(r: Result[A, String]): A = r match
        case Ok(v)    => v
        case Err(msg) => fail(s"invalid value for '$display': $msg")

      /** Interpret one spec's occurrences; `None` means a required value is missing. */
      def finishSpec(
          display: String,
          mode: Mode,
          default: Option[() => Any],
          occurrences: List[Occ]
      ): Option[Any] =
        mode match
          case Mode.Flag(fromCount, fromValue) =>
            occurrences.collect { case v: Occ.Val => v }.lastOption match
              case Some(v) =>
                fromValue match
                  case Some(f) => Some(orFail(v.display)(f(v.raw)))
                  case None    => fail(s"flag '${v.display}' does not take a value")
              case None if occurrences.nonEmpty =>
                Some(orFail(display)(fromCount(occurrences.length)))
              case None =>
                Some(default.map(_()).getOrElse(orFail(display)(fromCount(0))))
          case Mode.Single(read, optional) =>
            occurrences.lastOption match
              case Some(Occ.Val(raw, disp)) =>
                val v = orFail(disp)(read(raw))
                Some(if optional then Some(v) else v)
              case _ =>
                default.map(d => Some(d())).getOrElse(if optional then Some(None) else None)
          case Mode.Repeated(read, fromList) =>
            val vals = occurrences.collect { case Occ.Val(raw, disp) => orFail(disp)(read(raw)) }
            if vals.nonEmpty then Some(orFail(display)(fromList(vals)))
            else default.map(d => Some(d())).getOrElse(Some(orFail(display)(fromList(Nil))))

      val missing = mutable.ListBuffer.empty[String]

      cmd.opts.foreach { o =>
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
        fail(s"missing required $what: ${missing.mkString(", ")}")

      cmd.trailing.foreach { t =>
        values(t.index) = trailValue match
          case Some(v) => if t.optional then Some(v) else v
          case None    =>
            t.default.map(_()).getOrElse {
              if t.optional then None
              else
                t.build(Nil) match
                  case Ok(v)    => v
                  case Err(msg) => fail(s"missing arguments after '--': $msg")
            }
      }

      cmd.sub.foreach { g =>
        values(g.index) = subValue match
          case Some(v) => if g.optional then Some(v) else v
          case None    =>
            g.default.map(_()).getOrElse {
              if g.optional then None
              else fail(s"missing command (expected one of: ${g.cases.map(_.name).mkString(", ")})")
            }
      }

      cmd.finish(values) match
        case Ok(v)    => v
        case Err(msg) => fail(msg)
