package flagged

/** Behaviors cross-checked against what mainargs and case-app document for the same input, so that
  * deliberate differences stay deliberate. Each test names the library whose documented behavior it
  * was checked against; where flagged diverges on purpose, the test pins down *our* behavior and
  * the comment records the difference.
  */

case class ParityBasic(
    @short('v') verbose: Boolean = false,
    @short('o') output: String = "out.txt",
    maxRetries: Int = 3
) derives Parser.Command

case class ParityCount(
    @short('v') verbose: Count = Count(0)
) derives Parser.Command

case class ParityAuth(
    user: String,
    pass: String
) derives Parser.Shared

case class ParityApp(
    url: String,
    auth: ParityAuth
) derives Parser.Command

case class ParityLazyDefault(
    name: String,
    expensive: Int = sys.error("default evaluated")
) derives Parser.Command

case class ParityWide(
    f1: Int = 1,
    f2: Int = 2,
    f3: Int = 3,
    f4: Int = 4,
    f5: Int = 5,
    f6: Int = 6,
    f7: Int = 7,
    f8: Int = 8,
    f9: Int = 9,
    f10: Int = 10,
    f11: Int = 11,
    f12: Int = 12,
    f13: Int = 13,
    f14: Int = 14,
    f15: Int = 15,
    f16: Int = 16,
    f17: Int = 17,
    f18: Int = 18,
    f19: Int = 19,
    f20: Int = 20,
    f21: Int = 21,
    f22: Int = 22,
    f23: Int = 23
) derives Parser.Command

case class ParityIntArgs(
    @positional nums: List[Int] = Nil
) derives Parser.Command

case class ParityMaybeFlag(
    flag: Option[Boolean] = None
) derives Parser.Command

case class ParitySeqField(
    items: Seq[String] = Nil
) derives Parser.Command

case class ParityDefines(
    define: Map[String, Int] = Map.empty
) derives Parser.Command

case class ParityDigits(
    optFor29Name: Int = 0
) derives Parser.Command

case class ParityExactName(
    @name("myExact") value: Int = 0
) derives Parser.Command

case class ParityShortField(
    v: Int = 0
) derives Parser.Command

case class ParityHidden(
    visible: String = "a",
    @hidden secret: String = "b"
) derives Parser.Command

enum ParityTool derives Parser.CommandGroup:
  case Run(fast: Boolean = false)
  @hidden case Debug(level: Int = 0)

@version
case class ParityVersioned(
    input: String = ""
) derives Parser.Command

object ParityVersioned:
  given Versioned[ParityVersioned]:
    def version = "1.2.3"

@version
case class ParityDynVersioned(
    input: String = ""
) derives Parser.Command

object ParityDynVersioned:
  var current = "0.1.0"
  given Versioned[ParityDynVersioned]:
    def version = current

case class ParityAliased(
    @name("color") @name("colour") color: Boolean = false
) derives Parser.Command

enum ParityVcs derives Parser.CommandGroup:
  @name("checkout") @name("co") case Checkout(branch: String = "main")

enum ParityGit derives Parser.CommandGroup:
  @default case Status(@short('s') short: Boolean = false)
  case Push(remote: String = "origin")

case class ParityWrappedDefault(
    verbose: Boolean = false,
    action: ParityGit
) derives Parser.Command

case class ParityNet(
    @group("Network") host: String = "localhost",
    @group("Network") port: Int = 80,
    quiet: Boolean = false
) derives Parser.Command

case class ParityOut(
    color: Boolean = false,
    pager: Boolean = false
) derives Parser.Shared

case class ParityGrouped(
    input: String = "",
    @group("Output") out: ParityOut
) derives Parser.Command

class ParitySuite extends munit.FunSuite:

  def ok[A](r: ParseResult[A]): A = r match
    case Ok(a)                         => a
    case Err(ParseError.Help(t))       => fail(s"expected success, got help:\n$t")
    case Err(ParseError.Failure(m, _)) => fail(s"expected success, got failure: $m")

  def err[A](r: ParseResult[A]): String = r match
    case Err(ParseError.Failure(m, _)) => m
    case other                         => fail(s"expected failure, got $other")

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
      case Err(ParseError.Help(_)) => ()
      case other                   => fail(s"expected help, got $other")
  }

  test("an all-dash token is an unknown option (mainargs: treated as a plain value)") {
    val msg = err(Flagged.parse[ParityBasic](Seq("---")))
    assert(msg.contains("unknown option"), msg)
  }

  test("only the kebab-case spelling is accepted (mainargs: camelCase also matches)") {
    val msg = err(Flagged.parse[ParityBasic](Seq("--maxRetries", "7")))
    assert(msg.startsWith("unknown option '--maxRetries'"), msg)
  }

  test("an explicit @name is matched verbatim, never kebab-mapped (mainargs, case-app: same)") {
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

  test("@hidden options parse but are omitted from help (mainargs, case-app: same)") {
    assertEquals(
      ok(Flagged.parse[ParityHidden](Seq("--secret", "x"))),
      ParityHidden(secret = "x")
    )
    val help = Flagged.help[ParityHidden]
    assert(help.contains("--visible"), help)
    assert(!help.contains("--secret"), help)
  }

  test("@hidden subcommands are selectable but unlisted (case-app: same)") {
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
      case Err(ParseError.Help(t)) => assertEquals(t, "1.2.3")
      case other                   => fail(s"expected version output, got $other")
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
      "@version case class NoV(x: Int = 0) derives Parser.Command"
    )
    assert(e.contains("requires a given Versioned"), e)
  }

  test("Versioned is consulted when printed, not at derivation") {
    def versionOut(): String = Flagged.parse[ParityDynVersioned](Seq("--version")) match
      case Err(ParseError.Help(t)) => t
      case other                   => fail(s"expected version output, got $other")
    ParityDynVersioned.current = "0.1.0"
    assertEquals(versionOut(), "0.1.0")
    ParityDynVersioned.current = "0.2.0"
    assertEquals(versionOut(), "0.2.0")
  }

  test("repeated @name adds option aliases (case-app stacked @Name: same)") {
    assertEquals(ok(Flagged.parse[ParityAliased](Seq("--color"))), ParityAliased(true))
    assertEquals(ok(Flagged.parse[ParityAliased](Seq("--colour"))), ParityAliased(true))
    val help = Flagged.help[ParityAliased]
    assert(help.contains("--color"), help)
    assert(help.contains("alias: --colour"), help)
  }

  test("repeated @name on an enum case adds command aliases (case-app names: same)") {
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
      "case class C(@name(\"x\") a: Int = 0, @name(\"y\") @name(\"x\") b: Int = 0) derives Parser.Command"
    )
    assert(e.contains("duplicate option name"), e)
  }

  test("@default command runs when no command is given, with args forwarded (case-app: same)") {
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

  test("@default on a field is a compile error") {
    val e = compileErrors("case class C(@default x: Int = 0) derives Parser.Command")
    assert(e.contains("@default has no effect on a field"), e)
  }

  test("@group renders options under titled sections (case-app @Group: same)") {
    val help = Flagged.help[ParityNet]
    val net  = help.indexOf("Network options:")
    assert(net > 0, help)
    assert(help.indexOf("--host") > net, help)
    assert(help.indexOf("--port") > net, help)
    assert(help.indexOf("--quiet") < net, help)
  }

  test("@group on a spliced group titles its options (case-app: per-field only)") {
    val help = Flagged.help[ParityGrouped]
    val out  = help.indexOf("Output options:")
    assert(out > 0, help)
    assert(help.indexOf("--color") > out, help)
    assert(help.indexOf("--pager") > out, help)
  }

  test("@group misuse is a compile error") {
    val e = compileErrors(
      "case class C(@positional @group(\"X\") x: Int = 0) derives Parser.Command"
    )
    assert(e.contains("@group cannot be combined with @positional"), e)
  }

  test("@hidden misuse is a compile error") {
    val e = compileErrors(
      "case class C(@positional @hidden x: Int = 0) derives Parser.Command"
    )
    assert(e.contains("@hidden cannot be combined with @positional"), e)
  }

  test("option names are case-sensitive (case-app: same)") {
    val msg = err(Flagged.parse[ParityBasic](Seq("--Output", "x")))
    assert(msg.contains("unknown option '--Output'"), msg)
  }
