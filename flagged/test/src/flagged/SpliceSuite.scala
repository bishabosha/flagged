package flagged

// a shared options group, spliced into several commands below
case class LogOpts(
    @short('q') @help("Operate quietly") quiet: Boolean = false,
    @help("Log level") logLevel: String = "info"
) derives Parser.Shared

case class Serve(port: Int = 8080, logging: LogOpts = LogOpts()) derives Parser.Command

case class Token(@help("API token") token: String) derives Parser.Shared

// the same group spliced into @run method parameters
object pack:
  @run def build(target: String = "all", logging: LogOpts = LogOpts()): String =
    s"$target/${logging.quiet}/${logging.logLevel}"

object tools:
  @run def build(target: String = "all", logging: LogOpts = LogOpts()): String =
    s"$target/${logging.quiet}/${logging.logLevel}"

  @run def clean(logging: LogOpts = LogOpts()): String = s"clean/${logging.quiet}"

object publish:
  @run def push(remote: String = "origin", auth: Token): String = s"$remote:${auth.token}"

class SpliceSuite extends munit.FunSuite:

  def ok[A](r: ParseResult[A]): A = r match
    case Result.Ok(a)                         => a
    case Result.Err(ParseError.Help(t))       => fail(s"expected success, got help:\n$t")
    case Result.Err(ParseError.Failure(m, _)) => fail(s"expected success, got failure: $m")

  def err[A](r: ParseResult[A]): String = r match
    case Result.Err(ParseError.Failure(m, _)) => m
    case other                                => fail(s"expected failure, got $other")

  test("a product-shaped Parser field splices its options into the parent") {
    assertEquals(
      ok(Flagged.parse[Serve](Seq("--port", "9000", "-q", "--log-level", "debug"))),
      Serve(9000, LogOpts(quiet = true, logLevel = "debug"))
    )
  }

  test("spliced group defaults apply when its options are absent") {
    assertEquals(ok(Flagged.parse[Serve](Nil)), Serve(8080, LogOpts()))
  }

  test("spliced options appear in the parent's help") {
    Flagged.parse[Serve](Seq("--help")) match
      case Result.Err(ParseError.Help(t)) =>
        assert(t.contains("-q, --quiet"), t)
        assert(t.contains("--log-level <string>"), t)
        assert(t.contains("--port <int>"), t)
      case other => fail(s"expected help, got $other")
  }

  test("required options of a spliced group are enforced") {
    case class Auth(@help("API token") token: String) derives Parser.Shared
    case class App(url: String = "http://localhost", auth: Auth) derives Parser.Command
    val m = err(Flagged.parse[App](Nil))
    assert(m.contains("--token"), m)
    assertEquals(
      ok(Flagged.parse[App](Seq("--token", "t0"))),
      App("http://localhost", Auth("t0"))
    )
  }

  test("splices nest") {
    case class Inner(a: Int = 1) derives Parser.Shared
    case class Mid(b: Int = 2, inner: Inner = Inner()) derives Parser.Shared
    case class Outer(c: Int = 3, mid: Mid = Mid()) derives Parser.Command
    assertEquals(
      ok(Flagged.parse[Outer](Seq("--a", "10", "--b", "20", "--c", "30"))),
      Outer(30, Mid(20, Inner(10)))
    )
  }

  test("splicing works in @run method parameters") {
    // the group's options flatten into the method's own, at both shapes: a lone @run method
    // parsed flat, and a method selected from a group
    assertEquals(ok(Parser.method(pack).parse(Seq("--target", "x", "-q"))), "x/true/info")
    assertEquals(ok(Parser.method(pack).parse(Nil)), "all/false/info")
    val g = Parser.methods(tools)
    assertEquals(ok(g.parse(Seq("build", "--log-level", "debug"))), "all/false/debug")
    assertEquals(ok(g.parse(Seq("clean", "-q"))), "clean/true")
  }

  test("a spliced group's options show in a @run method's help") {
    Parser.methods(tools).parse(Seq("build", "--help")) match
      case Result.Err(ParseError.Help(t)) =>
        assert(t.contains("-q, --quiet"), t)
        assert(t.contains("--log-level <string>"), t)
        assert(t.contains("--target <string>"), t)
      case other => fail(s"expected help, got $other")
  }

  test("a spliced group's required options are enforced on a @run method") {
    val m = err(Parser.method(publish).parse(Nil))
    assert(m.contains("--token"), m)
    assertEquals(ok(Parser.method(publish).parse(Seq("--token", "t0"))), "origin:t0")
  }

  test("splicing works inside subcommand cases") {
    assertEquals(
      ok(Flagged.parse[Deploy](Seq("run", "--log-level", "warn"))),
      Deploy.Run(LogOpts(logLevel = "warn"))
    )
    assertEquals(ok(Flagged.parse[Deploy](Seq("stop"))), Deploy.Stop)
  }

  test("emap composes over a command parser (cross-field validation)") {
    case class Range(lo: Int = 0, hi: Int = 10)
    val p = Parser.Command
      .derived[Range]
      .emap(r =>
        if r.lo <= r.hi then Result.Ok(r)
        else Result.Err(s"lo (${r.lo}) must not exceed hi (${r.hi})")
      )
    assertEquals(ok(p.parse(Seq("--lo", "3"))), Range(3, 10))
    p.parse(Seq("--lo", "5", "--hi", "3")) match
      case Result.Err(ParseError.Failure(m, _)) => assert(m.contains("must not exceed"), m)
      case other                                => fail(s"expected failure, got $other")
  }

  test("a validated options group keeps its validation when spliced") {
    case class Window(min: Int = 0, max: Int = 100)
    given Parser.Shared[Window] = Parser.Shared
      .derived[Window]
      .emap(w => if w.min <= w.max then Result.Ok(w) else Result.Err("min must not exceed max"))
    case class App(label: String = "", window: Window = Window()) derives Parser.Command
    assertEquals(ok(Flagged.parse[App](Seq("--min", "5"))), App("", Window(5, 100)))
    val m = err(Flagged.parse[App](Seq("--min", "7", "--max", "2")))
    assert(m.contains("min must not exceed max"), m)
  }

  test("a name collision between parent and spliced group is a construction error") {
    case class Clash(@short('q') quick: Boolean = false, logging: LogOpts = LogOpts())
    val e = intercept[IllegalArgumentException](Parser.Command.derived[Clash])
    assert(e.getMessage.contains("duplicate short option '-q'"), e.getMessage)
    assert(e.getMessage.contains("options group 'logging'"), e.getMessage)
  }

  test("Option of a spliced group: None unless one of its options occurs") {
    case class MaybeLogged(port: Int = 8080, logging: Option[LogOpts] = None) derives Parser.Command
    assertEquals(ok(Flagged.parse[MaybeLogged](Seq("--port", "9000"))), MaybeLogged(9000, None))
    assertEquals(
      ok(Flagged.parse[MaybeLogged](Seq("-q"))),
      MaybeLogged(logging = Some(LogOpts(quiet = true)))
    )
  }

  test("a field default on a spliced group applies when none of its options occur") {
    case class Auth(user: String, token: String = "-") derives Parser.Shared
    case class Push(repo: String = ".", auth: Auth = Auth("anon")) derives Parser.Command
    assertEquals(ok(Flagged.parse[Push](Nil)), Push(".", Auth("anon")))
    assertEquals(ok(Flagged.parse[Push](Seq("--user", "u"))), Push(".", Auth("u")))
    // once any of its options occurs, the group is built: required options are enforced
    val msg = err(Flagged.parse[Push](Seq("--token", "t")))
    assert(msg.contains("--user"), msg)
  }

  test("an absent optional group skips its required options; a present one enforces them") {
    case class Auth(user: String, token: String) derives Parser.Shared
    case class Pull(repo: String = ".", auth: Option[Auth] = None) derives Parser.Command
    assertEquals(ok(Flagged.parse[Pull](Nil)), Pull(".", None))
    assertEquals(
      ok(Flagged.parse[Pull](Seq("--user", "u", "--token", "t"))),
      Pull(".", Some(Auth("u", "t")))
    )
    val msg = err(Flagged.parse[Pull](Seq("--user", "u")))
    assert(msg.contains("--token"), msg)
    assert(!msg.contains("--user"), msg)
  }

  test("@name on a spliced group prefixes its options, allowing repeat splices") {
    case class Endpoint(host: String = "localhost", port: Int = 80) derives Parser.Shared
    case class Proxy(
        @name("from") src: Endpoint = Endpoint(),
        @name("to") dst: Endpoint = Endpoint()
    ) derives Parser.Command
    assertEquals(
      ok(Flagged.parse[Proxy](Seq("--from-host", "a", "--to-port", "8080"))),
      Proxy(Endpoint(host = "a"), Endpoint(port = 8080))
    )
    val msg = err(Flagged.parse[Proxy](Seq("--host", "a")))
    assert(msg.contains("unknown option '--host'"), msg)
  }

  test("a prefixed splice drops the group's short aliases") {
    case class Verbosity(@short('v') level: Int = 0) derives Parser.Shared
    case class App2(@name("log") log: Verbosity = Verbosity()) derives Parser.Command
    assertEquals(ok(Flagged.parse[App2](Seq("--log-level", "3"))), App2(Verbosity(3)))
    val msg = err(Flagged.parse[App2](Seq("-v", "3")))
    assert(msg.contains("unknown option '-v'"), msg)
  }

  test("a shared group cannot contain a trailing field (compile time)") {
    val e = compileErrors(
      "case class WithTrailing(x: Int = 1, rest: Trailing = Trailing()) derives Parser.Shared"
    )
    assert(e.contains("cannot contain a trailing field"), e)
  }

  test("a shared group cannot contain positionals (compile time)") {
    val e =
      compileErrors("case class WithPos(@positional input: String = \"-\") derives Parser.Shared")
    assert(e.contains("cannot contain positional fields"), e)
  }

  test("a shared group cannot contain a @greedy option or a subcommand field (compile time)") {
    val e1 = compileErrors("case class G(@greedy xs: List[Int] = Nil) derives Parser.Shared")
    assert(e1.contains("cannot contain a @greedy option"), e1)
    val e2 = compileErrors("case class G(action: SimpleCmd) derives Parser.Shared")
    assert(e2.contains("cannot contain a subcommand field"), e2)
  }

  test("a full command cannot be a field (compile time)") {
    val e = compileErrors("case class Bad(x: Int = 0, tool: EmbeddedTool) derives Parser.Command")
    assert(e.contains("derive Parser.Shared"), e)
  }

  // ---- embedding a full command as a subcommand case -----------------------------

  test("a sole-field command-group case embeds a full command by substitution") {
    assertEquals(
      ok(Flagged.parse[Workbench](Seq("ext", "-f", "thing", "--", "a", "-b"))),
      Workbench.External(EmbeddedTool(force = true, target = "thing", Trailing(Vector("a", "-b"))))
    )
  }

  test("an embedded command keeps its own grammar and help") {
    Flagged.parse[Workbench](Seq("ext", "--help")) match
      case Result.Err(ParseError.Help(t)) =>
        assert(t.contains("-f, --force"), t)
        assert(t.contains("<target>"), t)
      case other => fail(s"expected help, got $other")
    Flagged.parse[Workbench](Seq("--help")) match
      case Result.Err(ParseError.Help(t)) =>
        assert(t.contains("ext"), t)
        assert(t.contains("An embedded external command"), t)
      case other => fail(s"expected help, got $other")
  }

  test("an embedded command reports its own errors under the case's name") {
    val m = err(Flagged.parse[Workbench](Seq("ext")))
    assert(m.contains("target"), m)
  }

  test("annotations on the embedded field are rejected (compile time)") {
    val e = compileErrors(
      "enum T derives Parser.CommandGroup:\n  case A(@name(\"x\") tool: EmbeddedTool)\n  case B"
    )
    assert(e.contains("annotations have no effect on an embedded command field"), e)
  }

enum Deploy derives Parser.CommandGroup:
  case Run(logging: LogOpts = LogOpts())
  case Stop

/** A full command "defined elsewhere", embedded wholesale as a subcommand case of [[Workbench]]. */
case class EmbeddedTool(
    @short('f') force: Boolean = false,
    @positional target: String,
    rest: Trailing = Trailing()
) derives Parser.Command

enum Workbench derives Parser.CommandGroup:
  case Run(logging: LogOpts = LogOpts())
  @name("ext") @help("An embedded external command")
  case External(tool: EmbeddedTool)
  case Stop
