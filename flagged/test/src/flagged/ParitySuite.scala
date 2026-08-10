package flagged

/** Behaviors cross-checked against what mainargs and case-app document for the same input, so that
  * deliberate differences stay deliberate. Each test names the library whose documented behavior it
  * was checked against; where flagged diverges on purpose, the test pins down *our* behavior and
  * the comment records the difference.
  */

case class ParityBasic(
    @opt(short = 'v') verbose: Boolean = false,
    @opt(short = 'o') output: String = "out.txt",
    @opt maxRetries: Int = 3
) derives Parser.Command

case class ParityCount(
    @opt(short = 'v') verbose: Count = Count(0)
) derives Parser.Command

case class ParityAuth(
    user: String,
    pass: String
) derives Parser.Shared

case class ParityApp(
    @opt url: String,
    auth: ParityAuth
) derives Parser.Command

case class ParityLazyDefault(
    @opt name: String,
    @opt expensive: Int = sys.error("default evaluated")
) derives Parser.Command

case class ParityWide(
    @opt f1: Int = 1,
    @opt f2: Int = 2,
    @opt f3: Int = 3,
    @opt f4: Int = 4,
    @opt f5: Int = 5,
    @opt f6: Int = 6,
    @opt f7: Int = 7,
    @opt f8: Int = 8,
    @opt f9: Int = 9,
    @opt f10: Int = 10,
    @opt f11: Int = 11,
    @opt f12: Int = 12,
    @opt f13: Int = 13,
    @opt f14: Int = 14,
    @opt f15: Int = 15,
    @opt f16: Int = 16,
    @opt f17: Int = 17,
    @opt f18: Int = 18,
    @opt f19: Int = 19,
    @opt f20: Int = 20,
    @opt f21: Int = 21,
    @opt f22: Int = 22,
    @opt f23: Int = 23
) derives Parser.Command

case class ParityIntArgs(
    nums: List[Int] = Nil
) derives Parser.Command

case class ParityMaybeFlag(
    @opt flag: Option[Boolean] = None
) derives Parser.Command

case class ParitySeqField(
    @opt items: Seq[String] = Nil
) derives Parser.Command

case class ParityDefines(
    @opt define: Map[String, Int] = Map.empty
) derives Parser.Command

case class ParityDigits(
    @opt optFor29Name: Int = 0
) derives Parser.Command

case class ParityExactName(
    @opt(name = "myExact") value: Int = 0
) derives Parser.Command

case class ParityShortField(
    @opt v: Int = 0
) derives Parser.Command

case class ParityHidden(
    @opt visible: String = "a",
    @opt(hidden = true) secret: String = "b"
) derives Parser.Command

enum ParityTool derives Parser.CommandGroup:
  case Run(@opt fast: Boolean = false)
  @cmd(hidden = true) case Debug(@opt level: Int = 0)

@version
case class ParityVersioned(
    @opt input: String = ""
) derives Parser.Command

object ParityVersioned:
  given Versioned[ParityVersioned]:
    def version = "1.2.3"

@version
case class ParityDynVersioned(
    @opt input: String = ""
) derives Parser.Command

object ParityDynVersioned:
  var current = "0.1.0"
  given Versioned[ParityDynVersioned]:
    def version = current

case class ParityAliased(
    @opt(name = "color", aliases = "colour" *: EmptyTuple) color: Boolean = false
) derives Parser.Command

enum ParityVcs derives Parser.CommandGroup:
  @cmd(name = "checkout", aliases = "co" *: EmptyTuple) case Checkout(@opt branch: String = "main")

enum ParityGit derives Parser.CommandGroup:
  @cmd(default = true) case Status(@opt(short = 's') short: Boolean = false)
  case Push(@opt remote: String = "origin")

case class ParityWrappedDefault(
    @opt verbose: Boolean = false,
    action: ParityGit
) derives Parser.Command

case class ParityRequiredWrappedDefault(
    @opt required: String,
    action: ParityGit
) derives Parser.Command

case class ParityNet(
    @opt(group = "Network") host: String = "localhost",
    @opt(group = "Network") port: Int = 80,
    @opt quiet: Boolean = false
) derives Parser.Command

case class ParityOut(
    color: Boolean = false,
    pager: Boolean = false
) derives Parser.Shared

case class ParityGrouped(
    @opt input: String = "",
    @opt(group = "Output") out: ParityOut
) derives Parser.Command

class ParitySuite extends munit.FunSuite:

  def ok[A](r: ParseResult[A]): A = r match
    case Result.Ok(a)                         => a
    case Result.Err(ParseError.Help(t))       => fail(s"expected success, got help:\n$t")
    case Result.Err(ParseError.Failure(m, _)) => fail(s"expected success, got failure: $m")

  def err[A](r: ParseResult[A]): String = r match
    case Result.Err(ParseError.Failure(m, _)) => m
    case other                                => fail(s"expected failure, got $other")

  // ---- mainargs parity: `=` handling -------------------------------------------

  test("--name= passes an empty string value (mainargs: same)") {
    assertEquals(ok(Flagged.parse[ParityBasic](Seq("--output="))), ParityBasic(output = ""))
  }

  test("--name=a=b splits on the first = only (mainargs: same)") {
    assertEquals(
      ok(Flagged.parse[ParityBasic](Seq("--output=bar=qux"))),
      ParityBasic(output = "bar=qux")
    )
  }

  test("-o= passes an empty attached value (mainargs: same)") {
    assertEquals(ok(Flagged.parse[ParityBasic](Seq("-o="))), ParityBasic(output = ""))
  }

  test("-okey=value keeps a non-leading = in the attached value (mainargs: same)") {
    assertEquals(
      ok(Flagged.parse[ParityBasic](Seq("-okey=value"))),
      ParityBasic(output = "key=value")
    )
  }

  test("a bundle ending in -x=value strips the leading = (mainargs: same)") {
    assertEquals(
      ok(Flagged.parse[ParityBasic](Seq("-vo=x.txt"))),
      ParityBasic(verbose = true, output = "x.txt")
    )
  }

  test("a counting flag rejects --flag=value (mainargs: unknown argument; we name the flag)") {
    val msg = err(Flagged.parse[ParityCount](Seq("--verbose=3")))
    assert(msg.contains("does not take a value"), msg)
  }

  // ---- mainargs parity: errors, defaults, arity ---------------------------------

  test("distinct error kinds are reported in one failure (mainargs, case-app: same)") {
    val msg = err(Flagged.parse[ParityBasic](Seq("--wrong", "--max-retries", "lol")))
    assert(msg.contains("unknown option '--wrong'"), msg)
    assert(msg.contains("invalid value for '--max-retries'"), msg)
  }

  test("all missing required arguments are reported together, across splices (mainargs: same)") {
    val msg = err(Flagged.parse[ParityApp](Nil))
    assert(msg.contains("--url"), msg)
    assert(msg.contains("--user"), msg)
    assert(msg.contains("--pass"), msg)
  }

  test("defaults are evaluated lazily, only when actually needed (mainargs, case-app: same)") {
    // deriving the parser and providing the value must never run the default's right-hand side
    assertEquals(
      ok(Flagged.parse[ParityLazyDefault](Seq("--name", "a", "--expensive", "5"))),
      ParityLazyDefault("a", 5)
    )
  }

  test("defaults are not evaluated when parsing has already failed") {
    val msg = err(Flagged.parse[ParityLazyDefault](Seq("--wrong")))
    assert(msg.contains("unknown option '--wrong'"), msg)
    assert(msg.contains("--name"), msg)
  }

  test("more than 22 fields derive and parse (mainargs: supported since 0.6.3)") {
    assertEquals(ok(Flagged.parse[ParityWide](Nil)), ParityWide())
    assertEquals(ok(Flagged.parse[ParityWide](Seq("--f-23", "99"))), ParityWide(f23 = 99))
  }

  test("a typed repeated positional reports the offending element (mainargs Leftover[Int]: same)") {
    assertEquals(ok(Flagged.parse[ParityIntArgs](Seq("1", "2"))), ParityIntArgs(List(1, 2)))
    val msg = err(Flagged.parse[ParityIntArgs](Seq("1", "x")))
    assert(msg.contains("<nums>"), msg)
  }

  test("--help is honored in any position (mainargs: first token only)") {
    Flagged.parse[ParityBasic](Seq("--output", "x", "--help")) match
      case Result.Err(ParseError.Help(_)) => ()
      case other                          => fail(s"expected help, got $other")
  }

  test("an all-dash token is an unknown option (mainargs: treated as a plain value)") {
    val msg = err(Flagged.parse[ParityBasic](Seq("---")))
    assert(msg.contains("unknown option"), msg)
  }

  test("only the kebab-case spelling is accepted (mainargs: camelCase also matches)") {
    val msg = err(Flagged.parse[ParityBasic](Seq("--maxRetries", "7")))
    assert(msg.startsWith("unknown option '--maxRetries'"), msg)
  }

  test(
    "an explicit @opt(name) is matched verbatim, never kebab-mapped (mainargs, case-app: same)"
  ) {
    assertEquals(ok(Flagged.parse[ParityExactName](Seq("--myExact", "5"))), ParityExactName(5))
    val msg = err(Flagged.parse[ParityExactName](Seq("--my-exact", "5")))
    assert(msg.contains("unknown option"), msg)
  }

  test("a single-letter field is a long option, no implicit short (mainargs, case-app: short)") {
    assertEquals(ok(Flagged.parse[ParityShortField](Seq("--v", "5"))), ParityShortField(5))
    val msg = err(Flagged.parse[ParityShortField](Seq("-v", "5")))
    assert(msg.contains("unknown option '-v'"), msg)
  }

  // ---- case-app parity -----------------------------------------------------------

  test("bare Option[Boolean] means Some(true), absent means None (case-app: same)") {
    assertEquals(ok(Flagged.parse[ParityMaybeFlag](Nil)), ParityMaybeFlag(None))
    assertEquals(ok(Flagged.parse[ParityMaybeFlag](Seq("--flag"))), ParityMaybeFlag(Some(true)))
    assertEquals(
      ok(Flagged.parse[ParityMaybeFlag](Seq("--flag=false"))),
      ParityMaybeFlag(Some(false))
    )
  }

  test("repeating a boolean flag replaces the value (mainargs, case-app: error)") {
    assertEquals(
      ok(Flagged.parse[ParityBasic](Seq("--verbose", "--verbose"))),
      ParityBasic(verbose = true)
    )
    assertEquals(ok(Flagged.parse[ParityBasic](Seq("-vv"))), ParityBasic(verbose = true))
    // the last mention wins, bare or valued
    assertEquals(
      ok(Flagged.parse[ParityBasic](Seq("--verbose", "--verbose=false"))),
      ParityBasic(verbose = false)
    )
    assertEquals(
      ok(Flagged.parse[ParityBasic](Seq("--verbose=false", "--verbose"))),
      ParityBasic(verbose = true)
    )
    assertEquals(ok(Flagged.parse[ParityCount](Seq("-vvv"))), ParityCount(Count(3)))
  }

  test("Seq fields accumulate like List and Vector (case-app: no generic Seq instance)") {
    assertEquals(
      ok(Flagged.parse[ParitySeqField](Seq("--items", "a", "--items", "b"))),
      ParitySeqField(Seq("a", "b"))
    )
  }

  test("Map fields accumulate k=v entries, split at the first = (mainargs: same)") {
    assertEquals(
      ok(Flagged.parse[ParityDefines](Seq("--define", "a=1", "--define", "b=2"))),
      ParityDefines(Map("a" -> 1, "b" -> 2))
    )
    val msg = err(Flagged.parse[ParityDefines](Seq("--define", "nope")))
    assert(msg.contains("string=int"), msg)
  }

  test("an absent repeated option is the empty collection (mainargs, case-app: same)") {
    assertEquals(ok(Flagged.parse[ParitySeqField](Nil)), ParitySeqField(Nil))
  }

  test("kebab-casing splits at digit boundaries (mainargs: same)") {
    assertEquals(
      ok(Flagged.parse[ParityDigits](Seq("--opt-for-29-name", "5"))),
      ParityDigits(5)
    )
    val msg = err(Flagged.parse[ParityDigits](Seq("--opt-for29-name", "5")))
    assert(msg.contains("unknown option"), msg)
  }

  test("@opt(hidden) options parse but are omitted from help (mainargs, case-app: same)") {
    assertEquals(
      ok(Flagged.parse[ParityHidden](Seq("--secret", "x"))),
      ParityHidden(secret = "x")
    )
    val help = Flagged.help[ParityHidden]
    assert(help.contains("--visible"), help)
    assert(!help.contains("--secret"), help)
  }

  test("@cmd(hidden) subcommands are selectable but unlisted (case-app: same)") {
    assertEquals(
      ok(Flagged.parse[ParityTool](Seq("debug", "--level", "2"))),
      ParityTool.Debug(2)
    )
    val help = Flagged.help[ParityTool]
    assert(help.contains("run"), help)
    assert(!help.contains("debug"), help)
  }

  test("@version adds --version and a help header (case-app @AppVersion: same)") {
    Flagged.parse[ParityVersioned](Seq("--version")) match
      case Result.Err(ParseError.Help(t)) => assertEquals(t, "1.2.3")
      case other                          => fail(s"expected version output, got $other")
    val help = Flagged.help[ParityVersioned]
    assert(help.startsWith("parity-versioned 1.2.3"), help)
    assert(help.contains("--version"), help)
  }

  test("@version on a field is a compile error") {
    val e = compileErrors(
      "case class C(@version x: Int = 0) derives Parser.Command"
    )
    assert(e.contains("@version has no effect on a field"), e)
  }

  test("@version without a Versioned instance is a compile error") {
    val e = compileErrors(
      "@version case class NoV(@opt x: Int = 0) derives Parser.Command"
    )
    assert(e.contains("requires a given Versioned"), e)
  }

  test("@version with a literal needs no Versioned instance") {
    @version("0.4.2") case class LitVersioned(@opt x: Int = 0) derives Parser.Command
    Flagged.parse[LitVersioned](Seq("--version")) match
      case Result.Err(ParseError.Help(t)) => assertEquals(t, "0.4.2")
      case other                          => fail(s"expected version output, got $other")
    val help = Flagged.help[LitVersioned]
    assert(help.startsWith("lit-versioned 0.4.2"), help)
  }

  test("a non-empty @version literal takes precedence over dynamic") {
    @version("9.9.9", dynamic = true) case class LitOver(@opt x: Int = 0) derives Parser.Command
    given Versioned[LitOver] = Versioned.of("3.0.0")
    Flagged.parse[LitOver](Seq("--version")) match
      case Result.Err(ParseError.Help(t)) => assertEquals(t, "9.9.9")
      case other                          => fail(s"expected version output, got $other")
  }

  test("an empty @version literal is not a version: dynamic applies") {
    @version("") case class EmptyLit(@opt x: Int = 0) derives Parser.Command
    given Versioned[EmptyLit] = Versioned.of("3.0.0")
    Flagged.parse[EmptyLit](Seq("--version")) match
      case Result.Err(ParseError.Help(t)) => assertEquals(t, "3.0.0")
      case other                          => fail(s"expected version output, got $other")
  }

  test("@version(dynamic = true) without a Versioned instance is a compile error") {
    val e = compileErrors(
      "@version(dynamic = true) case class NoV2(@opt x: Int = 0) derives Parser.Command"
    )
    assert(e.contains("requires a given Versioned"), e)
  }

  test("@version rejects a user option that would shadow --version") {
    @version case class OwnVersion(@opt version: String = "field") derives Parser.Command
    given Versioned[OwnVersion] = Versioned.of("1.0")
    val e = intercept[IllegalArgumentException](Parser.Command.derived[OwnVersion])
    assert(e.getMessage.contains("duplicate option name '--version'"), e.getMessage)
  }

  test("Versioned is consulted when printed, not at derivation") {
    def versionOut(): String = Flagged.parse[ParityDynVersioned](Seq("--version")) match
      case Result.Err(ParseError.Help(t)) => t
      case other                          => fail(s"expected version output, got $other")
    ParityDynVersioned.current = "0.1.0"
    assertEquals(versionOut(), "0.1.0")
    ParityDynVersioned.current = "0.2.0"
    assertEquals(versionOut(), "0.2.0")
  }

  test("@opt(aliases) adds option aliases (case-app stacked @Name: same)") {
    assertEquals(ok(Flagged.parse[ParityAliased](Seq("--color"))), ParityAliased(true))
    assertEquals(ok(Flagged.parse[ParityAliased](Seq("--colour"))), ParityAliased(true))
    val help = Flagged.help[ParityAliased]
    assert(help.contains("--color"), help)
    assert(help.contains("alias: --colour"), help)
  }

  test("@cmd(aliases) on an enum case adds command aliases (case-app names: same)") {
    assertEquals(
      ok(Flagged.parse[ParityVcs](Seq("co", "--branch", "dev"))),
      ParityVcs.Checkout("dev")
    )
    assertEquals(
      ok(Flagged.parse[ParityVcs](Seq("checkout"))),
      ParityVcs.Checkout("main")
    )
  }

  test("an alias colliding with a constant name is a compile error") {
    val e = compileErrors(
      "case class C(@opt(name = \"x\") a: Int = 0, " +
        "@opt(name = \"y\", aliases = \"x\" *: EmptyTuple) b: Int = 0) derives Parser.Command"
    )
    assert(e.contains("duplicate option name"), e)
  }

  test(
    "@cmd(default = true) command runs when no command is given, args forwarded (case-app: same)"
  ) {
    assertEquals(ok(Flagged.parse[ParityGit](Nil)), ParityGit.Status())
    assertEquals(ok(Flagged.parse[ParityGit](Seq("--short"))), ParityGit.Status(true))
    assertEquals(ok(Flagged.parse[ParityGit](Seq("-s"))), ParityGit.Status(true))
    assertEquals(ok(Flagged.parse[ParityGit](Seq("push"))), ParityGit.Push())
    val help = Flagged.help[ParityGit]
    assert(help.contains("(default)"), help)
    assert(help.contains("[<command>]"), help)
  }

  test("a nested default command receives arguments after parent options") {
    assertEquals(
      ok(Flagged.parse[ParityWrappedDefault](Seq("--short"))),
      ParityWrappedDefault(action = ParityGit.Status(true))
    )
    assertEquals(
      ok(Flagged.parse[ParityWrappedDefault](Seq("--verbose", "--short"))),
      ParityWrappedDefault(verbose = true, action = ParityGit.Status(true))
    )
  }

  test("a subcommand is not parsed until its parent has validated") {
    val msg = err(Flagged.parse[ParityRequiredWrappedDefault](Seq("--child-option")))
    assert(msg.contains("missing required argument: --required"), msg)
    assert(!msg.contains("child-option"), msg)
  }

  test("@cmd on a field is a compile error") {
    val e = compileErrors("case class C(@cmd(default = true) x: Int = 0) derives Parser.Command")
    assert(e.contains("@cmd has no effect on a field"), e)
  }

  test("@opt(group) renders options under titled sections (case-app @Group: same)") {
    val help = Flagged.help[ParityNet]
    val net  = help.indexOf("Network options:")
    assert(net > 0, help)
    assert(help.indexOf("--host") > net, help)
    assert(help.indexOf("--port") > net, help)
    assert(help.indexOf("--quiet") < net, help)
  }

  test("@opt(group) on a spliced group titles its options (case-app: per-field only)") {
    val help = Flagged.help[ParityGrouped]
    val out  = help.indexOf("Output options:")
    assert(out > 0, help)
    assert(help.indexOf("--color") > out, help)
    assert(help.indexOf("--pager") > out, help)
  }

  test("@opt(group) misuse is a compile error") {
    val e = compileErrors(
      "case class C(@opt(positional = true, group = \"X\") x: Int = 0) derives Parser.Command"
    )
    assert(e.contains("@opt(group) cannot be combined with @opt(positional)"), e)
  }

  test("@opt(hidden) misuse is a compile error") {
    val e = compileErrors(
      "case class C(@opt(positional = true, hidden = true) x: Int = 0) derives Parser.Command"
    )
    assert(e.contains("@opt(hidden) cannot be combined with @opt(positional)"), e)
  }

  test("option names are case-sensitive (case-app: same)") {
    val msg = err(Flagged.parse[ParityBasic](Seq("--Output", "x")))
    assert(msg.contains("unknown option '--Output'"), msg)
  }
