package claw.internal

import claw.{ParseError, ParseResult}
import steps.result.Result
import steps.result.Result.{Ok, Err, eval}
import steps.result.Result.eval.ok
import scala.collection.mutable

/** The token-stream parser. Interprets a `Command` tree against the argument list. */
private[claw] object Engine:

  def run(cmd: Command, prog: String, path: List[String], args: List[String]): ParseResult[Any] =
    Result:
      val full = (prog :: path).mkString(" ")
      def hint = s"Try '$full --help' for more information."
      def fail(msg: String): Nothing = eval.raise(ParseError.Failure(msg, hint))
      def helpNow(): Nothing = eval.raise(ParseError.Help(HelpFmt.render(cmd, prog, path)))

      val values = new Array[Any](cmd.arity)
      val isSet = new Array[Boolean](cmd.arity)
      val collected = mutable.LinkedHashMap.empty[Int, mutable.ListBuffer[Any]]
      val flagCounts = mutable.Map.empty[Int, Int].withDefaultValue(0)
      var rest = args
      var posIdx = 0
      var noMoreOpts = false

      def longOf(n: String) = cmd.opts.find(_.long == n)
      def shortOf(c: Char) = cmd.opts.find(_.short.contains(c))

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

      def readOr(read: String => Result[Any, String], raw: String, display: String): Any =
        read(raw) match
          case Ok(v)    => v
          case Err(msg) => fail(s"invalid value for '$display': $msg")

      def setParsed(spec: OptSpec, raw: String, display: String): Unit =
        spec.mode match
          case Mode.Flag(_, fromValue) =>
            fromValue match
              case None    => fail(s"flag '$display' does not take a value")
              case Some(f) =>
                values(spec.index) = readOr(f, raw, display)
                isSet(spec.index) = true
          case Mode.Single(read, optional) =>
            val v = readOr(read, raw, display)
            values(spec.index) = if optional then Some(v) else v
            isSet(spec.index) = true
          case Mode.Repeated(read, _) =>
            collected.getOrElseUpdate(spec.index, mutable.ListBuffer.empty) += readOr(read, raw, display)
            isSet(spec.index) = true

      def setFlag(spec: OptSpec): Unit =
        flagCounts(spec.index) += 1

      def handleFree(tok: String): Unit =
        cmd.sub match
          case Some(g) =>
            g.cases.find(_.name == tok) match
              case Some(sc) =>
                // .ok propagates the subcommand's Help/Failure to our caller unchanged
                val v = run(sc.command, prog, path :+ sc.name, rest).ok
                values(g.index) = if g.optional then Some(v) else v
                isSet(g.index) = true
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
            p.mode match
              case Mode.Repeated(read, _) =>
                collected.getOrElseUpdate(p.index, mutable.ListBuffer.empty) +=
                  readOr(read, tok, s"<${p.name}>")
                isSet(p.index) = true
              case Mode.Single(read, optional) =>
                val v = readOr(read, tok, s"<${p.name}>")
                values(p.index) = if optional then Some(v) else v
                isSet(p.index) = true
                posIdx += 1
              case Mode.Flag(_, _) =>
                fail(s"unexpected argument '$tok'") // positionals are never flags

      while rest.nonEmpty do
        val tok = rest.head
        rest = rest.tail
        // `-2` is a positional value unless a short option `-2` is actually defined
        val isFree =
          noMoreOpts || tok == "-" || !tok.startsWith("-") ||
            (isNegativeNumber(tok) && shortOf(tok(1)).isEmpty)
        if isFree then handleFree(tok)
        else if tok == "--" then noMoreOpts = true
        else if tok.startsWith("--") then
          val body = tok.drop(2)
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
              spec.mode match
                case Mode.Flag(_, _) =>
                  inlineValue match
                    case None    => setFlag(spec)
                    case Some(v) => setParsed(spec, v, s"--$nm")
                case _ =>
                  val raw = inlineValue.getOrElse(takeValue(s"--$nm"))
                  setParsed(spec, raw, s"--$nm")
        else
          // short option cluster: -v, -abc, -o value, -ovalue, -o=value
          var i = 1
          var consumedValue = false
          while i < tok.length && !consumedValue do
            val c = tok(i)
            if c == 'h' && shortOf('h').isEmpty then helpNow()
            shortOf(c) match
              case None =>
                fail(s"unknown option '-$c'")
              case Some(spec) =>
                spec.mode match
                  case Mode.Flag(_, _) =>
                    setFlag(spec)
                    i += 1
                  case _ =>
                    val attached = tok.drop(i + 1)
                    val raw =
                      if attached.nonEmpty then
                        if attached.startsWith("=") then attached.drop(1) else attached
                      else takeValue(s"-$c")
                    setParsed(spec, raw, s"-$c")
                    consumedValue = true

      def combineRepeated(display: String, fromList: List[Any] => Result[Any, String], elems: List[Any]): Any =
        fromList(elems) match
          case Ok(v)    => v
          case Err(msg) => fail(s"invalid value for '$display': $msg")

      // materialize repeated values
      collected.foreach { (idx, buf) =>
        val entry = cmd.opts
          .find(_.index == idx)
          .map(o => (s"--${o.long}", o.mode))
          .orElse(cmd.positionals.find(_.index == idx).map(p => (s"<${p.name}>", p.mode)))
        entry match
          case Some((display, Mode.Repeated(_, fromList))) =>
            values(idx) = combineRepeated(display, fromList, buf.toList)
          case _ =>
            values(idx) = buf.toList
      }

      def countedFlag(display: String, fromCount: Int => Result[Any, String], n: Int): Any =
        fromCount(n) match
          case Ok(v)    => v
          case Err(msg) => fail(s"invalid value for '$display': $msg")

      // apply defaults, collect missing
      val missing = mutable.ListBuffer.empty[String]
      cmd.opts.foreach { o =>
        if !isSet(o.index) then
          o.mode match
            case Mode.Flag(fromCount, _) =>
              // occurrences beat the field default; absent means count 0
              val n = flagCounts(o.index)
              values(o.index) =
                if n > 0 then countedFlag(s"--${o.long}", fromCount, n)
                else o.default.map(_()).getOrElse(countedFlag(s"--${o.long}", fromCount, 0))
            case _ =>
              o.default match
                case Some(d) => values(o.index) = d()
                case None =>
                  o.mode match
                    case Mode.Single(_, true)       => values(o.index) = None
                    case Mode.Single(_, false)      => missing += s"--${o.long}"
                    case Mode.Repeated(_, fromList) => values(o.index) = combineRepeated(s"--${o.long}", fromList, Nil)
                    case Mode.Flag(_, _)            => () // handled above
      }
      cmd.positionals.foreach { p =>
        if !isSet(p.index) then
          p.default match
            case Some(d) => values(p.index) = d()
            case None =>
              p.mode match
                case Mode.Single(_, true)       => values(p.index) = None
                case Mode.Single(_, false)      => missing += s"<${p.name}>"
                case Mode.Repeated(_, fromList) => values(p.index) = combineRepeated(s"<${p.name}>", fromList, Nil)
                case Mode.Flag(fromCount, _)    => values(p.index) = countedFlag(s"<${p.name}>", fromCount, 0)
      }
      if missing.nonEmpty then
        val what = if missing.sizeIs == 1 then "argument" else "arguments"
        fail(s"missing required $what: ${missing.mkString(", ")}")

      cmd.sub.foreach { g =>
        if !isSet(g.index) then
          g.default match
            case Some(d) => values(g.index) = d()
            case None if g.optional => values(g.index) = None
            case None =>
              fail(s"missing command (expected one of: ${g.cases.map(_.name).mkString(", ")})")
      }

      cmd.finish(values) match
        case Ok(v)    => v
        case Err(msg) => fail(msg)
