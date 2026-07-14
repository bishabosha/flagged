package claw

import claw.internal.{Command, Engine, HelpFmt}

/** A derived command-line parser for `A`.
  *
  * Obtain one with `derives Parser` on a case class / enum, or explicitly with
  * `Parser.derived[A]`.
  */
sealed trait Parser[A]:
  private[claw] def command: Command
  private[claw] def defaultProg: String

  /** Parse `args`, reporting help/errors as values on the `Err` channel. */
  final def parse(args: Seq[String]): ParseResult[A] = parse(args, defaultProg)

  final def parse(args: Seq[String], prog: String): ParseResult[A] =
    Engine.run(command, prog, Nil, args.toList).asInstanceOf[ParseResult[A]]

  /** Parse `args`; on `--help` print the help screen and exit 0, on error print a
    * message to stderr and exit 2. Intended for `@main` methods and scripts.
    */
  final def parseOrExit(args: Seq[String]): A = parseOrExit(args, defaultProg)

  final def parseOrExit(args: Seq[String], prog: String): A =
    parse(args, prog) match
      case Ok(a) => a
      case Err(ParseError.Help(text)) =>
        println(text)
        sys.exit(0)
      case Err(ParseError.Failure(message, hint)) =>
        System.err.println(s"$prog: $message")
        if hint.nonEmpty then System.err.println(hint)
        sys.exit(2)

  /** The rendered top-level help screen. */
  final def help: String = help(defaultProg)

  final def help(prog: String): String = HelpFmt.render(command, prog, Nil)

object Parser:
  /** Derivation entry point for `derives Parser` clauses; also usable directly:
    * `given Parser[Config] = Parser.derived`.
    */
  inline def derived[A]: Parser[A] = ${ internal.ParserMacros.deriveParser[A] }

  /** Called by macro-generated code. Not intended for direct use. */
  def make[A](cmd: Command, prog: String): Parser[A] = new Parser[A]:
    private[claw] val command = cmd
    private[claw] val defaultProg = prog

/** Convenience entry points:
  *
  * {{{
  * @main def app(args: String*): Unit =
  *   val cfg = Claw.parseOrExit[Config](args)
  * }}}
  */
object Claw:
  def parse[A](args: Seq[String])(using p: Parser[A]): ParseResult[A] = p.parse(args)
  def parse[A](args: Seq[String], prog: String)(using p: Parser[A]): ParseResult[A] = p.parse(args, prog)
  def parseOrExit[A](args: Seq[String])(using p: Parser[A]): A = p.parseOrExit(args)
  def parseOrExit[A](args: Seq[String], prog: String)(using p: Parser[A]): A = p.parseOrExit(args, prog)
  def help[A](using p: Parser[A]): String = p.help
  def help[A](prog: String)(using p: Parser[A]): String = p.help(prog)
