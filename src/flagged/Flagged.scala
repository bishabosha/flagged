package flagged

/** Convenience entry points:
  *
  * {{{
  * @main def app(args: String*): Unit =
  *   val cfg = Flagged.parseOrExit[Config](args)
  * }}}
  */
object Flagged:
  def parse[A](args: Seq[String])(using p: Parser[A]): ParseResult[A]               = p.parse(args)
  def parse[A](args: Seq[String], prog: String)(using p: Parser[A]): ParseResult[A] =
    p.parse(args, prog)
  def parseOrExit[A](args: Seq[String])(using p: Parser[A]): A               = p.parseOrExit(args)
  def parseOrExit[A](args: Seq[String], prog: String)(using p: Parser[A]): A =
    p.parseOrExit(args, prog)
  def help[A](using p: Parser[A]): String                  = p.help
  def help[A](prog: String)(using p: Parser[A]): String    = p.help(prog)
  def helpAll[A](using p: Parser[A]): String               = p.helpAll
  def helpAll[A](prog: String)(using p: Parser[A]): String = p.helpAll(prog)
