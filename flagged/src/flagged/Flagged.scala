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

  /** Parse by invoking the `@run` methods of object `obj`. A lone method parses directly — its
    * parameters are the options — while several methods (or nested `@run` objects) become
    * subcommands, so the call site does not change as commands are added.
    */
  inline def parseMethods[T](obj: T, args: Seq[String])(
      using mm: meta.MethodsMirror[T]
  ): ParseResult[mm.MirroredResult] =
    methodsParser[T](obj).parse(args)

  inline def parseMethods[T](obj: T, args: Seq[String], prog: String)(
      using mm: meta.MethodsMirror[T]
  ): ParseResult[mm.MirroredResult] =
    methodsParser[T](obj).parse(args, prog)

  inline def parseMethodsOrExit[T](obj: T, args: Seq[String])(
      using mm: meta.MethodsMirror[T]
  ): mm.MirroredResult =
    methodsParser[T](obj).parseOrExit(args)

  inline def parseMethodsOrExit[T](obj: T, args: Seq[String], prog: String)(
      using mm: meta.MethodsMirror[T]
  ): mm.MirroredResult =
    methodsParser[T](obj).parseOrExit(args, prog)

  private inline def methodsParser[T](obj: T)(
      using mm: meta.MethodsMirror[T]
  ): Parser[mm.MirroredResult] =
    internal.DeriveMethods.parser[T, mm.type, mm.MirroredResult](obj, mm)

  def help[A](using p: Parser[A]): String                  = p.help
  def help[A](prog: String)(using p: Parser[A]): String    = p.help(prog)
  def helpAll[A](using p: Parser[A]): String               = p.helpAll
  def helpAll[A](prog: String)(using p: Parser[A]): String = p.helpAll(prog)
