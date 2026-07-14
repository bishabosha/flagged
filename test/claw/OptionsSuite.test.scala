package claw


case class Basic(
    @short('v') @help("Increase verbosity") verbose: Boolean = false,
    @short('o') @help("Output file") output: String = "out.txt",
    @help("Number of retries") maxRetries: Int = 3,
    tag: Option[String] = None
) derives Parser

case class Required(
    host: String,
    port: Int = 8080
) derives Parser

case class Collections(
    @short('f') file: List[String] = Nil,
    nums: Vector[Int] = Vector.empty
) derives Parser

case class WithPositionals(
    @positional @help("Input path") input: String,
    @positional output: Option[String] = None,
    @short('n') dryRun: Boolean = false
) derives Parser

case class VarargPositionals(
    @short('v') verbose: Boolean = false,
    @positional files: List[String] = Nil
) derives Parser

class OptionsSuite extends munit.FunSuite:

  def ok[A](r: ParseResult[A]): A = r match
    case Ok(a)                          => a
    case Err(ParseError.Help(t))        => fail(s"expected success, got help:\n$t")
    case Err(ParseError.Failure(m, _))  => fail(s"expected success, got failure: $m")

  def err[A](r: ParseResult[A]): String = r match
    case Err(ParseError.Failure(m, _)) => m
    case other                         => fail(s"expected failure, got $other")

  test("all defaults") {
    assertEquals(ok(Claw.parse[Basic](Nil)), Basic())
  }

  test("long options with separate values") {
    assertEquals(
      ok(Claw.parse[Basic](Seq("--output", "x.txt", "--max-retries", "7"))),
      Basic(output = "x.txt", maxRetries = 7)
    )
  }

  test("long options with = values") {
    assertEquals(
      ok(Claw.parse[Basic](Seq("--output=x.txt", "--max-retries=7"))),
      Basic(output = "x.txt", maxRetries = 7)
    )
  }

  test("long flag") {
    assertEquals(ok(Claw.parse[Basic](Seq("--verbose"))), Basic(verbose = true))
  }

  test("flag with explicit value") {
    assertEquals(ok(Claw.parse[Basic](Seq("--verbose=false"))), Basic(verbose = false))
    assertEquals(ok(Claw.parse[Basic](Seq("--verbose=yes"))), Basic(verbose = true))
  }

  test("short option with separate value") {
    assertEquals(ok(Claw.parse[Basic](Seq("-o", "x.txt"))), Basic(output = "x.txt"))
  }

  test("short option with attached value") {
    assertEquals(ok(Claw.parse[Basic](Seq("-ox.txt"))), Basic(output = "x.txt"))
  }

  test("short option with =value") {
    assertEquals(ok(Claw.parse[Basic](Seq("-o=x.txt"))), Basic(output = "x.txt"))
  }

  test("short flag") {
    assertEquals(ok(Claw.parse[Basic](Seq("-v"))), Basic(verbose = true))
  }

  test("Option field") {
    assertEquals(ok(Claw.parse[Basic](Seq("--tag", "beta"))), Basic(tag = Some("beta")))
    assertEquals(ok(Claw.parse[Basic](Nil)).tag, None)
  }

  test("kebab-case naming") {
    assertEquals(ok(Claw.parse[Basic](Seq("--max-retries", "9"))).maxRetries, 9)
  }

  test("last occurrence wins for single-value options") {
    assertEquals(ok(Claw.parse[Basic](Seq("-o", "a", "-o", "b"))).output, "b")
  }

  test("missing required option") {
    val m = err(Claw.parse[Required](Nil))
    assert(m.contains("--host"), m)
  }

  test("required option provided") {
    assertEquals(ok(Claw.parse[Required](Seq("--host", "example.com"))), Required("example.com"))
  }

  test("invalid int value") {
    val m = err(Claw.parse[Required](Seq("--host", "h", "--port", "banana")))
    assert(m.contains("--port") && m.contains("banana"), m)
  }

  test("unknown long option with suggestion") {
    val m = err(Claw.parse[Basic](Seq("--outpot", "x")))
    assert(m.contains("unknown option '--outpot'"), m)
    assert(m.contains("did you mean '--output'"), m)
  }

  test("unknown short option") {
    val m = err(Claw.parse[Basic](Seq("-z")))
    assert(m.contains("unknown option '-z'"), m)
  }

  test("option missing its value") {
    val m = err(Claw.parse[Basic](Seq("--output")))
    assert(m.contains("requires a value"), m)
  }

  test("option value that looks like an option is rejected") {
    val m = err(Claw.parse[Basic](Seq("--output", "--verbose")))
    assert(m.contains("requires a value"), m)
  }

  test("repeated list option") {
    assertEquals(
      ok(Claw.parse[Collections](Seq("-f", "a", "--file", "b", "-fc"))),
      Collections(file = List("a", "b", "c"))
    )
  }

  test("repeated vector option with element parsing") {
    assertEquals(
      ok(Claw.parse[Collections](Seq("--nums", "1", "--nums", "2"))),
      Collections(nums = Vector(1, 2))
    )
  }

  test("positional arguments in order") {
    assertEquals(
      ok(Claw.parse[WithPositionals](Seq("in.txt", "out.txt"))),
      WithPositionals("in.txt", Some("out.txt"))
    )
  }

  test("optional positional omitted") {
    assertEquals(ok(Claw.parse[WithPositionals](Seq("in.txt"))), WithPositionals("in.txt", None))
  }

  test("missing required positional") {
    val m = err(Claw.parse[WithPositionals](Nil))
    assert(m.contains("<input>"), m)
  }

  test("positionals mixed with options") {
    assertEquals(
      ok(Claw.parse[WithPositionals](Seq("-n", "in.txt", "out.txt"))),
      WithPositionals("in.txt", Some("out.txt"), dryRun = true)
    )
    assertEquals(
      ok(Claw.parse[WithPositionals](Seq("in.txt", "-n", "out.txt"))),
      WithPositionals("in.txt", Some("out.txt"), dryRun = true)
    )
  }

  test("too many positionals") {
    val m = err(Claw.parse[WithPositionals](Seq("a", "b", "c")))
    assert(m.contains("unexpected argument 'c'"), m)
  }

  test("repeated positional collects everything") {
    assertEquals(
      ok(Claw.parse[VarargPositionals](Seq("a", "b", "-v", "c"))),
      VarargPositionals(verbose = true, files = List("a", "b", "c"))
    )
  }

  test("-- ends option parsing") {
    assertEquals(
      ok(Claw.parse[VarargPositionals](Seq("a", "--", "-v", "--weird"))),
      VarargPositionals(verbose = false, files = List("a", "-v", "--weird"))
    )
  }

  test("bundled short flags") {
    case class Flags(
        @short('a') alpha: Boolean = false,
        @short('b') beta: Boolean = false,
        @short('c') gamma: Boolean = false
    ) derives Parser
    assertEquals(ok(Claw.parse[Flags](Seq("-abc"))), Flags(true, true, true))
  }

  test("bundled flags ending in a value option") {
    case class Mixed(
        @short('v') verbose: Boolean = false,
        @short('o') output: String = ""
    ) derives Parser
    assertEquals(ok(Claw.parse[Mixed](Seq("-vo", "x"))), Mixed(true, "x"))
    assertEquals(ok(Claw.parse[Mixed](Seq("-vox"))), Mixed(true, "x"))
  }

  test("negative numbers are values, not options") {
    case class Neg(@positional n: Int, offset: Int = 0) derives Parser
    assertEquals(ok(Claw.parse[Neg](Seq("-5", "--offset", "-3"))), Neg(-5, -3))
  }

  test("single dash is a positional value") {
    case class Dash(@positional input: String) derives Parser
    assertEquals(ok(Claw.parse[Dash](Seq("-"))), Dash("-"))
  }

  test("@name overrides the long name") {
    case class Named(@name("out") @help("x") outputFileName: String = "a") derives Parser
    assertEquals(ok(Claw.parse[Named](Seq("--out", "b"))), Named("b"))
    val m = err(Claw.parse[Named](Seq("--output-file-name", "b")))
    assert(m.contains("unknown option"), m)
  }
