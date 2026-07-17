package flagged

// a shared options group, spliced into several commands below
case class LogOpts(
    @short('q') @help("Operate quietly") quiet: Boolean = false,
    @help("Log level") logLevel: String = "info"
) derives Parser.Command

case class Serve(port: Int = 8080, logging: LogOpts = LogOpts()) derives Parser.Command

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
      ok(Flagged.parse[Serve](Seq("--port", "9000", "-q", "--log-level", "debug"))),
      Serve(9000, LogOpts(quiet = true, logLevel = "debug"))
    )
  }

  test("spliced group defaults apply when its options are absent") {
    assertEquals(ok(Flagged.parse[Serve](Nil)), Serve(8080, LogOpts()))
  }

  test("spliced options appear in the parent's help") {
    Flagged.parse[Serve](Seq("--help")) match
      case Err(ParseError.Help(t)) =>
        assert(t.contains("-q, --quiet"), t)
        assert(t.contains("--log-level <string>"), t)
        assert(t.contains("--port <int>"), t)
      case other => fail(s"expected help, got $other")
  }

  test("required options of a spliced group are enforced") {
    case class Auth(@help("API token") token: String) derives Parser.Command
    case class App(url: String = "http://localhost", auth: Auth) derives Parser.Command
    val m = err(Flagged.parse[App](Nil))
    assert(m.contains("--token"), m)
    assertEquals(
      ok(Flagged.parse[App](Seq("--token", "t0"))),
      App("http://localhost", Auth("t0"))
    )
  }

  test("splices nest") {
    case class Inner(a: Int = 1) derives Parser.Command
    case class Mid(b: Int = 2, inner: Inner = Inner()) derives Parser.Command
    case class Outer(c: Int = 3, mid: Mid = Mid()) derives Parser.Command
    assertEquals(
      ok(Flagged.parse[Outer](Seq("--a", "10", "--b", "20", "--c", "30"))),
      Outer(30, Mid(20, Inner(10)))
    )
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
      .parser
      .emap(r => if r.lo <= r.hi then Ok(r) else Err(s"lo (${r.lo}) must not exceed hi (${r.hi})"))
    assertEquals(ok(p.parse(Seq("--lo", "3"))), Range(3, 10))
    p.parse(Seq("--lo", "5", "--hi", "3")) match
      case Err(ParseError.Failure(m, _)) => assert(m.contains("must not exceed"), m)
      case other                         => fail(s"expected failure, got $other")
  }

  test("a validated options group keeps its validation when spliced") {
    case class Window(min: Int = 0, max: Int = 100)
    given Parser.Aux[Window, Parser.Shape.Command] = Parser.Command
      .derived[Window]
      .parser
      .emap(w => if w.min <= w.max then Ok(w) else Err("min must not exceed max"))
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

  test("Option of a spliced group is rejected at compile time") {
    val e = compileErrors("case class Bad(logging: Option[LogOpts] = None) derives Parser.Command")
    assert(e.contains("Option of a spliced options group"), e)
  }

  test("a spliced group with a trailing field is rejected") {
    // previously produced a silent null: the splice copies only the child's options,
    // so the child's trailing slot could never be filled
    case class WithTrailing(x: Int = 1, rest: Trailing = Trailing(Nil)) derives Parser.Command
    case class Bad(g: WithTrailing = WithTrailing())
    val e = intercept[IllegalArgumentException](Parser.Command.derived[Bad])
    assert(e.getMessage.contains("cannot contain a trailing field"), e.getMessage)
  }

  test("a spliced group with positionals is rejected") {
    case class WithPos(@positional input: String = "-") derives Parser.Command
    case class Bad(files: WithPos = WithPos())
    val e = intercept[IllegalArgumentException](Parser.Command.derived[Bad])
    assert(e.getMessage.contains("cannot contain positional fields"), e.getMessage)
  }

enum Deploy derives Parser.CommandGroup:
  case Run(logging: LogOpts = LogOpts())
  case Stop
