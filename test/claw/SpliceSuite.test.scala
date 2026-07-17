package claw

// a shared options group, spliced into several commands below
case class LogOpts(
    @short('q') @help("Operate quietly") quiet: Boolean = false,
    @help("Log level") logLevel: String = "info"
) derives Parser

case class Serve(port: Int = 8080, logging: LogOpts = LogOpts()) derives Parser

class SpliceSuite extends munit.FunSuite:

  def ok[A](r: ParseResult[A]): A = r match
    case Ok(a)                         => a
    case Err(ParseError.Help(t))       => fail(s"expected success, got help:\n$t")
    case Err(ParseError.Failure(m, _)) => fail(s"expected success, got failure: $m")

  def err[A](r: ParseResult[A]): String = r match
    case Err(ParseError.Failure(m, _)) => m
    case other                         => fail(s"expected failure, got $other")

  test("a product-shaped Parser field splices its options into the parent") {
    assertEquals(
      ok(Claw.parse[Serve](Seq("--port", "9000", "-q", "--log-level", "debug"))),
      Serve(9000, LogOpts(quiet = true, logLevel = "debug"))
    )
  }

  test("spliced group defaults apply when its options are absent") {
    assertEquals(ok(Claw.parse[Serve](Nil)), Serve(8080, LogOpts()))
  }

  test("spliced options appear in the parent's help") {
    Claw.parse[Serve](Seq("--help")) match
      case Err(ParseError.Help(t)) =>
        assert(t.contains("-q, --quiet"), t)
        assert(t.contains("--log-level <string>"), t)
        assert(t.contains("--port <int>"), t)
      case other => fail(s"expected help, got $other")
  }

  test("required options of a spliced group are enforced") {
    case class Auth(@help("API token") token: String) derives Parser
    case class App(url: String = "http://localhost", auth: Auth) derives Parser
    val m = err(Claw.parse[App](Nil))
    assert(m.contains("--token"), m)
    assertEquals(
      ok(Claw.parse[App](Seq("--token", "t0"))),
      App("http://localhost", Auth("t0"))
    )
  }

  test("splices nest") {
    case class Inner(a: Int = 1) derives Parser
    case class Mid(b: Int = 2, inner: Inner = Inner()) derives Parser
    case class Outer(c: Int = 3, mid: Mid = Mid()) derives Parser
    assertEquals(
      ok(Claw.parse[Outer](Seq("--a", "10", "--b", "20", "--c", "30"))),
      Outer(30, Mid(20, Inner(10)))
    )
  }

  test("splicing works inside subcommand cases") {
    assertEquals(
      ok(Claw.parse[Deploy](Seq("run", "--log-level", "warn"))),
      Deploy.Run(LogOpts(logLevel = "warn"))
    )
    assertEquals(ok(Claw.parse[Deploy](Seq("stop"))), Deploy.Stop)
  }

  test("emap composes over a command parser (cross-field validation)") {
    case class Range(lo: Int = 0, hi: Int = 10)
    val p = Parser.derived[Range].emap(r =>
      if r.lo <= r.hi then Ok(r) else Err(s"lo (${r.lo}) must not exceed hi (${r.hi})")
    )
    assertEquals(ok(p.parse(Seq("--lo", "3"))), Range(3, 10))
    p.parse(Seq("--lo", "5", "--hi", "3")) match
      case Err(ParseError.Failure(m, _)) => assert(m.contains("must not exceed"), m)
      case other                         => fail(s"expected failure, got $other")
  }

  test("a validated options group keeps its validation when spliced") {
    case class Window(min: Int = 0, max: Int = 100)
    given Parser[Window] = Parser.derived[Window].emap(w =>
      if w.min <= w.max then Ok(w) else Err("min must not exceed max")
    )
    case class App(label: String = "", window: Window = Window()) derives Parser
    assertEquals(ok(Claw.parse[App](Seq("--min", "5"))), App("", Window(5, 100)))
    val m = err(Claw.parse[App](Seq("--min", "7", "--max", "2")))
    assert(m.contains("min must not exceed max"), m)
  }

  test("a name collision between parent and spliced group is a construction error") {
    case class Clash(@short('q') quick: Boolean = false, logging: LogOpts = LogOpts())
    val e = intercept[IllegalArgumentException](Parser.derived[Clash])
    assert(e.getMessage.contains("duplicate short option '-q'"), e.getMessage)
    assert(e.getMessage.contains("options group 'logging'"), e.getMessage)
  }

  test("Option of a spliced group is rejected") {
    case class Bad(logging: Option[LogOpts] = None)
    val e = intercept[IllegalArgumentException](Parser.derived[Bad])
    assert(e.getMessage.contains("Option of a spliced options group"), e.getMessage)
  }

  test("a spliced group with positionals is rejected") {
    case class WithPos(@positional input: String = "-") derives Parser
    case class Bad(files: WithPos = WithPos())
    val e = intercept[IllegalArgumentException](Parser.derived[Bad])
    assert(e.getMessage.contains("cannot contain positional fields"), e.getMessage)
  }

enum Deploy derives Parser:
  case Run(logging: LogOpts = LogOpts())
  case Stop
