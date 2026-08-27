package flagged

case class SepConfig(
    @opt(short = 'f') foo: String = "",
    @opt bar: Int = 0,
    @opt(short = 'v') verbose: Boolean = false,
    @opt define: Map[String, String] = Map.empty
) derives Parser.Command

class SettingsSuite extends munit.FunSuite:

  def ok[A](r: ParseResult[A]): A = r match
    case Result.Ok(a) => a
    case other        => fail(s"expected success, got $other")

  val colon = ParserSettings(ValueSeparator.Colon)

  test("colon mode parses --opt:value"):
    val c = ok(Parser[SepConfig].parse(Seq("--foo:abc", "--bar:42"), settings = colon))
    assertEquals(c, SepConfig(foo = "abc", bar = 42))

  test("colon mode parses the attached short form -f:v"):
    val c = ok(Parser[SepConfig].parse(Seq("-f:abc", "-v"), settings = colon))
    assertEquals(c, SepConfig(foo = "abc", verbose = true))

  test("colon mode leaves the pair separator of Map entries at ="):
    val c = ok(Parser[SepConfig].parse(Seq("--define:a=1", "--define:b=2"), settings = colon))
    assertEquals(c.define, Map("a" -> "1", "b" -> "2"))

  test("colon mode keeps separate-token values with colons intact"):
    val c = ok(Parser[SepConfig].parse(Seq("--foo", "8080:80"), settings = colon))
    assertEquals(c.foo, "8080:80")

  test("colon mode applies to subcommands"):
    val r = ok(Flagged.parse[GitCli](Seq("clone", "--depth:1", "u"), settings = colon))
    assertEquals(r, GitCli.Clone(depth = 1, url = "u"))

  test("colon mode does not split at ="):
    Parser[SepConfig].parse(Seq("--foo=abc"), settings = colon) match
      case Result.Err(ParseError.Failure(msg, _)) => assert(msg.contains("unknown option"))
      case other                                  => fail(s"expected failure, got $other")
    val c = ok(Parser[SepConfig].parse(Seq("--foo:a=b"), settings = colon))
    assertEquals(c.foo, "a=b")

  test("default settings are unchanged: --opt=value works, --opt:value is unknown"):
    val c = ok(Parser[SepConfig].parse(Seq("--foo=abc")))
    assertEquals(c.foo, "abc")
    Parser[SepConfig].parse(Seq("--foo:abc")) match
      case Result.Err(ParseError.Failure(msg, _)) => assert(msg.contains("unknown option"))
      case other                                  => fail(s"expected failure, got $other")

enum GitCli derives Parser.CommandGroup:
  case Clone(@opt depth: Int = 0, @opt(positional = true) url: String)
  case Status()
