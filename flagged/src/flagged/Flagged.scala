package flagged

/** Convenience entry points:
  *
  * {{{
  * @main def app(args: String*): Unit =
  *   val cfg = Flagged.parseOrExit[Config](args)   // Config derives Parser.Command
  *
  * @main def cli(args: String*): Unit =
  *   Flagged.parseOrExit[this.type](args)          // @cmd methods in this file
  * }}}
  */
object Flagged:
  /** Parse `args`, reporting help and errors as values on the `Err` channel. The grammar comes from
    * implicit search — the [[Parser]] for `A` when one exists, otherwise `A`'s `@cmd` methods (see
    * [[Entry]]) — so `A` may be a `derives` type or an object holding commands.
    */
  def parse[A](args: Seq[String])(using e: Entry[A]): ParseResult[e.Out] = e.parser.parse(args)

  /** [[parse]] with `prog` as the program name shown in usage, help, and error messages, instead of
    * one derived from the type, method, or object name.
    */
  def parse[A](args: Seq[String], prog: String)(using e: Entry[A]): ParseResult[e.Out] =
    e.parser.parse(args, prog)

  /** Parse `args`, selecting the grammar like [[parse]]; on `--help` print the help screen and exit
    * 0, on error print a message to stderr and exit 2. Intended for `@main` methods and scripts.
    */
  def parseOrExit[A](args: Seq[String])(using e: Entry[A]): e.Out =
    val p = e.parser
    orExit(p.parse(args), p.typeName)

  /** [[parseOrExit]] with `prog` as the program name shown in usage, help, and error messages,
    * instead of one derived from the type, method, or object name.
    */
  def parseOrExit[A](args: Seq[String], prog: String)(using e: Entry[A]): e.Out =
    orExit(e.parser.parse(args, prog), prog)

  private def orExit[A](result: ParseResult[A], prog: String): A = result match
    case Result.Ok(a)                      => a
    case Result.Err(ParseError.Help(text)) =>
      println(text)
      internal.PlatformExit.exit(0)
    case Result.Err(ParseError.Failure(message, hint)) =>
      System.err.println(s"$prog: $message")
      if hint.nonEmpty then System.err.println(hint)
      internal.PlatformExit.exit(2)

  /** The rendered top-level help screen for `A`, without parsing anything. */
  def help[A](using e: Entry[A]): String = e.parser.help

  /** [[help]] with `prog` as the program name, instead of one derived from the type name. */
  def help[A](prog: String)(using e: Entry[A]): String = e.parser.help(prog)

  /** [[help]] including hidden options and subcommands — what `--help-all` prints. */
  def helpAll[A](using e: Entry[A]): String = e.parser.helpAll

  /** [[helpAll]] with `prog` as the program name, instead of one derived from the type name. */
  def helpAll[A](prog: String)(using e: Entry[A]): String = e.parser.helpAll(prog)

  /** How the entry points parse an `A`, resolved by implicit search: with the [[Parser]] for `A`
    * when one exists (`Out = A`), otherwise by deriving from `A`'s `@cmd` methods (`Out` is the
    * invoked method's result — for an object, the union across its commands).
    */
  @annotation.implicitNotFound(
    "cannot parse ${A}: no given Parser[${A}] instance, and no @cmd methods to derive one from"
  )
  trait Entry[A]:
    type Out
    def parser: Parser[Out]

  trait EntryLowPriority:
    /** Carrier for the `@cmd` branch: named so inline expansion shares one class. */
    class MethodsEntry[T, O](p: () => Parser[O]) extends Entry[T]:
      type Out = O
      def parser: Parser[O] = p()

    /** `@cmd` methods: the result union comes from the recursively summoned [[runner.MethodEntry]]
      * tower. `ValueOf` is the cheap gate — it fails implicit search for a non-singleton `T` before
      * the mirror macro is expanded, so those call sites get this trait's `implicitNotFound`
      * message rather than a macro abort. The value itself is not needed: commands are static
      * object methods.
      */
    inline given [T] => (ValueOf[T]) => (r: runner.MethodEntry[T])
      => (Entry[T] { type Out = r.Out }) =
      MethodsEntry[T, r.Out](() => internal.DeriveMethods.parser[T, r.type, r.Out](r))

  object Entry extends EntryLowPriority:
    given [A] => (p: Parser[A]) => (Entry[A] { type Out = A }) =
      new Entry[A]:
        type Out = A
        def parser: Parser[A] = p
