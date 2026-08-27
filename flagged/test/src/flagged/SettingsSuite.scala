package flagged

case class SepConfig(
    @opt(short = 'f') foo: String = "",
    @opt bar: Int = 0,
    @opt(short = 'v') verbose: Boolean = false,
    @opt define: Map[String, String] = Map.empty,
    @opt limit: Option[Int] = None
) derives Parser.Command

case class MultiConfig(
    @opt qux: List[String] = Nil,
    @opt(split = ',') env: Vector[String] = Vector.empty,
    @opt(split = ':') ports: Set[Int] = Set.empty,
    @opt point: (Int, Int) = (0, 0),
    @opt(greedy = true) nums: List[Int] = Nil
) derives Parser.Command

class SettingsSuite extends munit.FunSuite:

  def ok[A](r: ParseResult[A]): A = r match
    case Result.Ok(a) => a
    case other        => fail(s"expected success, got $other")

  val colon       = ParserSettings(ValueSeparator.Colon)
  val scalacStyle = ParserSettings(ValueSeparator.Colon, LongPrefix.AnyDash)

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

  test("colon mode parses valued flags and Option fields inline"):
    val c = ok(Parser[SepConfig].parse(Seq("--verbose:false", "--limit:5"), settings = colon))
    assertEquals(c.verbose, false)
    assertEquals(c.limit, Some(5))

  test("colon mode accumulates repeated collections, attached and separate mixed"):
    val c = ok(Parser[MultiConfig].parse(Seq("--qux:a", "--qux", "b", "--qux:c"), settings = colon))
    assertEquals(c.qux, List("a", "b", "c"))

  test("colon mode combines with @opt(split = ',')"):
    val c = ok(Parser[MultiConfig].parse(Seq("--env:A,B", "--env:C"), settings = colon))
    assertEquals(c.env, Vector("A", "B", "C"))

  test("colon mode splits the option token once, then @opt(split = ':') divides the value"):
    val c = ok(Parser[MultiConfig].parse(Seq("--ports:8080:80"), settings = colon))
    assertEquals(c.ports, Set(8080, 80))

  test("colon mode keeps products token-per-element, rejecting the inline form"):
    val c = ok(Parser[MultiConfig].parse(Seq("--point", "3", "4"), settings = colon))
    assertEquals(c.point, (3, 4))
    Parser[MultiConfig].parse(Seq("--point:3"), settings = colon) match
      case Result.Err(ParseError.Failure(msg, _)) => assert(msg.contains("takes 2 values"))
      case other                                  => fail(s"expected failure, got $other")

  test("colon mode: a greedy option still consumes free tokens, and its inline form exactly one"):
    val c = ok(Parser[MultiConfig].parse(Seq("--nums", "10", "20", "30"), settings = colon))
    assertEquals(c.nums, List(10, 20, 30))
    val d = ok(Parser[MultiConfig].parse(Seq("--nums:10", "--qux:a"), settings = colon))
    assertEquals(d.nums, List(10))
    assertEquals(d.qux, List("a"))

  test("scalac style parses a real scalac command line, single-dash spellings included"):
    val c = ok(
      Parser[ScalacConfig].parse(
        Seq(
          "-classpath", "lib/a.jar:lib/b.jar", "-d", "out/classes", "-source:3.4-migration",
          "-encoding", "utf8", "-color:never", "-release:17",
          "-language:strictEquality,fewerBraces", "-deprecation", "-explain", "-Xmax-inlines:64",
          "-Werror", "-Wunused:imports,locals", "-Wvalue-discard",
          "-Wconf:cat=deprecation:s,msg=legacy:e", "Main.scala", "Utils.scala"
        ),
        settings = scalacStyle
      )
    )
    assertEquals(
      c,
      ScalacConfig(
        classpath = "lib/a.jar:lib/b.jar",
        outDir = "out/classes",
        source = "3.4-migration",
        encoding = "utf8",
        color = ColorMode.Never,
        release = Some(17),
        language = List("strictEquality", "fewerBraces"),
        deprecation = true,
        explain = true,
        maxInlines = 64,
        wError = true,
        wUnused = List("imports", "locals"),
        wValueDiscard = true,
        wConf = List("cat=deprecation:s", "msg=legacy:e"),
        files = Vector("Main.scala", "Utils.scala")
      )
    )

  test("scalac style: an attached classpath splits only at the first colon"):
    val c = ok(
      Parser[ScalacConfig].parse(Seq("-classpath:lib/a.jar:lib/b.jar"), settings = scalacStyle)
    )
    assertEquals(c.classpath, "lib/a.jar:lib/b.jar")

  test("single-dash longs win over cluster interpretation; unmatched tokens stay clusters"):
    val c = ok(Parser[SepConfig].parse(Seq("-verbose", "-f:abc"), settings = scalacStyle))
    assertEquals(c.verbose, true)
    assertEquals(c.foo, "abc")

  test("any-dash mode resolves -help and keeps the double-dash spellings working"):
    Parser[ScalacConfig].parse(Seq("-help"), settings = scalacStyle) match
      case Result.Err(ParseError.Help(text)) => assert(text.contains("Usage"))
      case other                             => fail(s"expected help, got $other")
    val c = ok(Parser[ScalacConfig].parse(Seq("--Werror", "A.scala"), settings = scalacStyle))
    assertEquals(c.wError, true)

  test("any-dash mode reports a typo'd long name whole, with a suggestion"):
    Parser[ScalacConfig].parse(Seq("-sourc:3.4"), settings = scalacStyle) match
      case Result.Err(ParseError.Failure(msg, _)) =>
        assert(msg.contains("unknown option '-sourc'"), msg)
        assert(msg.contains("did you mean '-source'?"), msg)
      case other => fail(s"expected failure, got $other")

  test("default settings do not resolve single-dash longs"):
    Parser[ScalacConfig].parse(Seq("-Werror", "A.scala")) match
      case Result.Err(ParseError.Failure(msg, _)) => assert(msg.contains("unknown option"))
      case other                                  => fail(s"expected failure, got $other")

  test("default settings are unchanged: --opt=value works, --opt:value is unknown"):
    val c = ok(Parser[SepConfig].parse(Seq("--foo=abc")))
    assertEquals(c.foo, "abc")
    Parser[SepConfig].parse(Seq("--foo:abc")) match
      case Result.Err(ParseError.Failure(msg, _)) => assert(msg.contains("unknown option"))
      case other                                  => fail(s"expected failure, got $other")

enum GitCli derives Parser.CommandGroup:
  case Clone(@opt depth: Int = 0, @opt(positional = true) url: String)
  case Status()

// A Scala 3 compiler-shaped grammar: scalac spells long settings after a single dash and
// attaches values with a colon (`-Werror`, `-source:3.4`, `-Xmax-inlines:64`,
// `-Wunused:imports,locals`), while values that themselves contain colons
// (`-classpath lib/a.jar:lib/b.jar`, `-Wconf:cat=deprecation:s`) stay unmangled because only
// the first separator of the option token splits.
enum ColorMode derives Parser.Enumerated:
  case Never, Always, Auto

case class ScalacConfig(
    @opt classpath: String = "",
    @opt(name = "d") outDir: String = ".",
    @opt source: String = "3.7",
    @opt encoding: String = "utf8",
    @opt color: ColorMode = ColorMode.Auto,
    @opt release: Option[Int] = None,
    @opt(split = ',') language: List[String] = Nil,
    @opt deprecation: Boolean = false,
    @opt explain: Boolean = false,
    @opt(name = "Xmax-inlines") maxInlines: Int = 32,
    @opt(name = "Werror") wError: Boolean = false,
    @opt(name = "Wunused", split = ',') wUnused: List[String] = Nil,
    @opt(name = "Wvalue-discard") wValueDiscard: Boolean = false,
    @opt(name = "Wconf", split = ',') wConf: List[String] = Nil,
    @opt(positional = true) files: Vector[String] = Vector.empty
) derives Parser.Command
