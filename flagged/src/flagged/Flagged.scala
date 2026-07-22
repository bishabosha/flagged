package flagged

/** Convenience entry points:
  *
  * {{{
  * @main def app(args: String*): Unit =
  *   val cfg = Flagged.parseOrExit[Config](args)
  * }}}
  */
object Flagged:
  /** Parse `args` with the [[Parser]] for `A`, reporting help and errors as values on the `Err`
    * channel.
    */
  def parse[A](args: Seq[String])(using p: Parser[A]): ParseResult[A] = p.parse(args)

  /** [[parse]] with `prog` as the program name shown in usage, help, and error messages, instead of
    * one derived from the type name.
    */
  def parse[A](args: Seq[String], prog: String)(using p: Parser[A]): ParseResult[A] =
    p.parse(args, prog)

  /** Parse `args` with the [[Parser]] for `A`; on `--help` print the help screen and exit 0, on
    * error print a message to stderr and exit 2. Intended for `@main` methods and scripts.
    */
  def parseOrExit[A](args: Seq[String])(using p: Parser[A]): A = p.parseOrExit(args)

  /** [[parseOrExit]] with `prog` as the program name shown in usage, help, and error messages,
    * instead of one derived from the type name.
    */
  def parseOrExit[A](args: Seq[String], prog: String)(using p: Parser[A]): A =
    p.parseOrExit(args, prog)

  /** Parse `args` by invoking the `@run` methods of object `obj`. A lone method parses directly —
    * its parameters are the options — while several methods (or nested `@run` objects) become
    * subcommands, so the call site does not change as commands are added. Help and errors are
    * reported as values on the `Err` channel.
    */
  inline def parseMethods[T](obj: T, args: Seq[String])(
      using mm: meta.MethodsMirror[T]
  ): ParseResult[mm.MirroredResult] =
    methodsParser[T](obj).parse(args)

  /** [[parseMethods]] with `prog` as the program name shown in usage, help, and error messages,
    * instead of one derived from the method or object name.
    */
  inline def parseMethods[T](obj: T, args: Seq[String], prog: String)(
      using mm: meta.MethodsMirror[T]
  ): ParseResult[mm.MirroredResult] =
    methodsParser[T](obj).parse(args, prog)

  /** Parse `args` by invoking the `@run` methods of object `obj`, adapting to a lone method or a
    * group like [[parseMethods]]; on `--help` print the help screen and exit 0, on error print a
    * message to stderr and exit 2. Intended for `@main` methods and scripts.
    */
  inline def parseMethodsOrExit[T](obj: T, args: Seq[String])(
      using mm: meta.MethodsMirror[T]
  ): mm.MirroredResult =
    methodsParser[T](obj).parseOrExit(args)

  /** [[parseMethodsOrExit]] with `prog` as the program name shown in usage, help, and error
    * messages, instead of one derived from the method or object name.
    */
  inline def parseMethodsOrExit[T](obj: T, args: Seq[String], prog: String)(
      using mm: meta.MethodsMirror[T]
  ): mm.MirroredResult =
    methodsParser[T](obj).parseOrExit(args, prog)

  /** The parser over `obj`'s `@run` members: a whole command for a lone method, a subcommand group
    * otherwise (see [[internal.DeriveMethods.parser]]).
    */
  private inline def methodsParser[T](obj: T)(
      using mm: meta.MethodsMirror[T]
  ): Parser[mm.MirroredResult] =
    internal.DeriveMethods.parser[T, mm.type, mm.MirroredResult](obj, mm)

  /** The rendered top-level help screen for `A`, without parsing anything. */
  def help[A](using p: Parser[A]): String = p.help

  /** [[help]] with `prog` as the program name, instead of one derived from the type name. */
  def help[A](prog: String)(using p: Parser[A]): String = p.help(prog)

  /** [[help]] including `@hidden` options and subcommands — what `--help-all` prints. */
  def helpAll[A](using p: Parser[A]): String = p.helpAll

  /** [[helpAll]] with `prog` as the program name, instead of one derived from the type name. */
  def helpAll[A](prog: String)(using p: Parser[A]): String = p.helpAll(prog)
