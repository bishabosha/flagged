package flagged

case class Basic(
    @opt(help = "Increase verbosity", short = 'v') verbose: Boolean = false,
    @opt(help = "Output file", short = 'o') output: String = "out.txt",
    @opt(help = "Number of retries") maxRetries: Int = 3,
    @opt tag: Option[String] = None
) derives Parser.Command

case class Required(
    @opt host: String,
    @opt port: Int = 8080
) derives Parser.Command

case class Collections(
    @opt(short = 'f') file: List[String] = Nil,
    @opt nums: Vector[Int] = Vector.empty
) derives Parser.Command

case class FactoryCollections(
    @opt tag: Set[String] = Set.empty,
    @opt level: scala.collection.immutable.SortedSet[Int] =
      scala.collection.immutable.SortedSet.empty,
    @opt raw: scala.collection.immutable.ArraySeq[String] =
      scala.collection.immutable.ArraySeq.empty
) derives Parser.Command

case class WithPositionals(
    @opt(help = "Input path", positional = true) input: String,
    output: Option[String] = None,
    @opt(short = 'n') dryRun: Boolean = false
) derives Parser.Command

case class VarargPositionals(
    @opt(short = 'v') verbose: Boolean = false,
    files: List[String] = Nil
) derives Parser.Command

class OptionsSuite extends munit.FunSuite:

  def ok[A](r: ParseResult[A]): A = r match
    case Result.Ok(a)                         => a
    case Result.Err(ParseError.Help(t))       => fail(s"expected success, got help:\n$t")
    case Result.Err(ParseError.Failure(m, _)) => fail(s"expected success, got failure: $m")

  def err[A](r: ParseResult[A]): String = r match
    case Result.Err(ParseError.Failure(m, _)) => m
    case other                                => fail(s"expected failure, got $other")

  test("all defaults") {
    assertEquals(ok(Flagged.parse[Basic](Nil)), Basic())
  }

  test("long options with separate values") {
    assertEquals(
      ok(Flagged.parse[Basic](Seq("--output", "x.txt", "--max-retries", "7"))),
      Basic(output = "x.txt", maxRetries = 7)
    )
  }

  test("long options with = values") {
    assertEquals(
      ok(Flagged.parse[Basic](Seq("--output=x.txt", "--max-retries=7"))),
      Basic(output = "x.txt", maxRetries = 7)
    )
  }

  test("long flag") {
    assertEquals(ok(Flagged.parse[Basic](Seq("--verbose"))), Basic(verbose = true))
  }

  test("flag with explicit value") {
    assertEquals(ok(Flagged.parse[Basic](Seq("--verbose=false"))), Basic(verbose = false))
    assertEquals(ok(Flagged.parse[Basic](Seq("--verbose=yes"))), Basic(verbose = true))
  }

  test("short option with separate value") {
    assertEquals(ok(Flagged.parse[Basic](Seq("-o", "x.txt"))), Basic(output = "x.txt"))
  }

  test("short option with attached value") {
    assertEquals(ok(Flagged.parse[Basic](Seq("-ox.txt"))), Basic(output = "x.txt"))
  }

  test("short option with =value") {
    assertEquals(ok(Flagged.parse[Basic](Seq("-o=x.txt"))), Basic(output = "x.txt"))
  }

  test("short flag") {
    assertEquals(ok(Flagged.parse[Basic](Seq("-v"))), Basic(verbose = true))
  }

  test("Option field") {
    assertEquals(ok(Flagged.parse[Basic](Seq("--tag", "beta"))), Basic(tag = Some("beta")))
    assertEquals(ok(Flagged.parse[Basic](Nil)).tag, None)
  }

  test("kebab-case naming") {
    assertEquals(ok(Flagged.parse[Basic](Seq("--max-retries", "9"))).maxRetries, 9)
  }

  test("last occurrence wins for single-value options") {
    assertEquals(ok(Flagged.parse[Basic](Seq("-o", "a", "-o", "b"))).output, "b")
  }

  test("missing required option") {
    val m = err(Flagged.parse[Required](Nil))
    assert(m.contains("--host"), m)
  }

  test("required option provided") {
    assertEquals(ok(Flagged.parse[Required](Seq("--host", "example.com"))), Required("example.com"))
  }

  test("invalid int value") {
    val m = err(Flagged.parse[Required](Seq("--host", "h", "--port", "banana")))
    assert(m.contains("--port") && m.contains("banana"), m)
  }

  test("unknown long option with suggestion") {
    val m = err(Flagged.parse[Basic](Seq("--outpot", "x")))
    assert(m.contains("unknown option '--outpot'"), m)
    assert(m.contains("did you mean '--output'"), m)
  }

  test("unknown short option") {
    val m = err(Flagged.parse[Basic](Seq("-z")))
    assert(m.contains("unknown option '-z'"), m)
  }

  test("option missing its value") {
    val m = err(Flagged.parse[Basic](Seq("--output")))
    assert(m.contains("requires a value"), m)
  }

  test("option value that looks like an option is rejected") {
    val m = err(Flagged.parse[Basic](Seq("--output", "--verbose")))
    assert(m.contains("requires a value"), m)
  }

  test("repeated options grow past the initial buffer") {
    // more than four occurrences of a List (builder collector) and of a custom repeated (the
    // default array collector, exercising its growth path)
    val many = (1 to 9).flatMap(i => Seq("--file", i.toString))
    assertEquals(
      ok(Flagged.parse[Collections](many)).file,
      (1 to 9).map(_.toString).toList
    )
    given Parser.Repeated[String] = Parser.repeated[String, String](l => Result.Ok(l.mkString(",")))
    case class Joined(@opt file: String = "") derives Parser.Command
    assertEquals(
      ok(Flagged.parse[Joined](many)).file,
      (1 to 9).mkString(",")
    )
  }

  test("repeated list option") {
    assertEquals(
      ok(Flagged.parse[Collections](Seq("-f", "a", "--file", "b", "-fc"))),
      Collections(file = List("a", "b", "c"))
    )
  }

  test("repeated vector option with element parsing") {
    assertEquals(
      ok(Flagged.parse[Collections](Seq("--nums", "1", "--nums", "2"))),
      Collections(nums = Vector(1, 2))
    )
  }

  test("any collection with a Factory works as a repeated option") {
    assertEquals(
      ok(
        Flagged.parse[FactoryCollections](
          Seq(
            "--tag", "a", "--tag", "b", "--tag", "a", "--level", "3", "--level", "1", "--raw", "x"
          )
        )
      ),
      FactoryCollections(
        tag = Set("a", "b"),
        level = scala.collection.immutable.SortedSet(1, 3),
        raw = scala.collection.immutable.ArraySeq("x")
      )
    )
  }

  test("the k=v Map instance wins over the Factory fallback even with a tuple Value in scope") {
    given Parser.Value[(String, Int)] =
      Parser.of("pair")(s => Result.Ok((s, 0))) // would parse without '=' if it were used
    case class M(@opt define: Map[String, Int] = Map.empty) derives Parser.Command
    assertEquals(
      ok(Flagged.parse[M](Seq("--define", "a=1", "--define", "b=2"))),
      M(Map("a" -> 1, "b" -> 2))
    )
    val msg = err(Flagged.parse[M](Seq("--define", "nope")))
    assert(msg.contains("is not in string=int form"), msg)
  }

  test("positional arguments in order") {
    assertEquals(
      ok(Flagged.parse[WithPositionals](Seq("in.txt", "out.txt"))),
      WithPositionals("in.txt", Some("out.txt"))
    )
  }

  test("optional positional omitted") {
    assertEquals(ok(Flagged.parse[WithPositionals](Seq("in.txt"))), WithPositionals("in.txt", None))
  }

  test("missing required positional") {
    val m = err(Flagged.parse[WithPositionals](Nil))
    assert(m.contains("<input>"), m)
  }

  test("positionals mixed with options") {
    assertEquals(
      ok(Flagged.parse[WithPositionals](Seq("-n", "in.txt", "out.txt"))),
      WithPositionals("in.txt", Some("out.txt"), dryRun = true)
    )
    assertEquals(
      ok(Flagged.parse[WithPositionals](Seq("in.txt", "-n", "out.txt"))),
      WithPositionals("in.txt", Some("out.txt"), dryRun = true)
    )
  }

  test("too many positionals") {
    val m = err(Flagged.parse[WithPositionals](Seq("a", "b", "c")))
    assert(m.contains("unexpected argument 'c'"), m)
  }

  test("repeated positional collects everything") {
    assertEquals(
      ok(Flagged.parse[VarargPositionals](Seq("a", "b", "-v", "c"))),
      VarargPositionals(verbose = true, files = List("a", "b", "c"))
    )
  }

  test("-- ends option parsing") {
    assertEquals(
      ok(Flagged.parse[VarargPositionals](Seq("a", "--", "-v", "--weird"))),
      VarargPositionals(verbose = false, files = List("a", "-v", "--weird"))
    )
  }

  test("bundled short flags") {
    case class Flags(
        @opt(short = 'a') alpha: Boolean = false,
        @opt(short = 'b') beta: Boolean = false,
        @opt(short = 'c') gamma: Boolean = false
    ) derives Parser.Command
    assertEquals(ok(Flagged.parse[Flags](Seq("-abc"))), Flags(true, true, true))
  }

  test("bundled flags ending in a value option") {
    case class Mixed(
        @opt(short = 'v') verbose: Boolean = false,
        @opt(short = 'o') output: String = ""
    ) derives Parser.Command
    assertEquals(ok(Flagged.parse[Mixed](Seq("-vo", "x"))), Mixed(true, "x"))
    assertEquals(ok(Flagged.parse[Mixed](Seq("-vox"))), Mixed(true, "x"))
  }

  test("negative numbers are values, not options") {
    case class Neg(n: Int, @opt offset: Int = 0) derives Parser.Command
    assertEquals(ok(Flagged.parse[Neg](Seq("-5", "--offset", "-3"))), Neg(-5, -3))
  }

  test("single dash is a positional value") {
    case class Dash(input: String) derives Parser.Command
    assertEquals(ok(Flagged.parse[Dash](Seq("-"))), Dash("-"))
  }

  test("@opt(name) overrides the long name") {
    case class Named(@opt(name = "out", help = "x") outputFileName: String = "a")
        derives Parser.Command
    assertEquals(ok(Flagged.parse[Named](Seq("--out", "b"))), Named("b"))
    val m = err(Flagged.parse[Named](Seq("--output-file-name", "b")))
    assert(m.contains("unknown option"), m)
  }

  test("conflicting annotations are compile errors") {
    val e = compileErrors(
      "case class C(@opt(short = 'x', positional = true) a: Int = 0) derives Parser.Command"
    )
    assert(e.contains("@opt(short) cannot be combined with @opt(positional)"), e)
  }

  test("shape/annotation conflicts are compile errors for shape-refined instances") {
    val e1 = compileErrors("case class C(x: Option[List[Int]]) derives Parser.Command")
    assert(e1.contains("Option of a repeated Parser"), e1)
    val e2 = compileErrors(
      "case class C(@opt(positional = true) t: Trailing = Trailing()) derives Parser.Command"
    )
    assert(e2.contains("@opt(positional) cannot be combined with a trailing field"), e2)
    val e3 = compileErrors(
      "case class C(@opt(short = 't') t: Trailing = Trailing()) derives Parser.Command"
    )
    assert(e3.contains("@opt(short) cannot be combined with a trailing field"), e3)
    // the Parser.Command witness keeps derived instances shape-refined, so this is
    // static too — a derives clause no longer erases the shape
    val e4 = compileErrors(
      "case class C(@opt(positional = true) action: SimpleAction) derives Parser.Command"
    )
    assert(e4.contains("@opt(positional) cannot be combined with a subcommand field"), e4)
  }

  test("cross-field and annotation rules for command shapes are compile errors") {
    val e1 = compileErrors(
      "case class C(a: SimpleAction, b: SimpleAction) derives Parser.Command"
    )
    assert(e1.contains("only one subcommand field is supported per command"), e1)
    val e2 = compileErrors(
      "case class C(x: Int = 0, action: SimpleAction) derives Parser.Command"
    )
    assert(e2.contains("mixing positional fields with a subcommand field"), e2)
    val e3 = compileErrors(
      "case class C(@opt(short = 'a') action: SimpleAction) derives Parser.Command"
    )
    assert(e3.contains("@opt(short) has no effect on a subcommand field"), e3)
    val e4 = compileErrors(
      "case class C(@opt(short = 'g') g: LogOpts = LogOpts()) derives Parser.Command"
    )
    assert(e4.contains("@opt(short) has no effect on a spliced options group"), e4)
  }

  test("a shape-erased instance is rejected statically") {
    val e = compileErrors(
      """given Parser[Byte] = Parser.of[Byte]("b")(s => Result.Err("no"))
         case class C(b: Byte = 0) derives Parser.Command"""
    )
    assert(e.contains("not statically known"), e)
  }

  test("bare flags are rejected statically in Option and positional position") {
    val e1 = compileErrors("case class C(v: Option[Count] = None) derives Parser.Command")
    assert(e1.contains("cannot be used inside Option"), e1)
    val e2 = compileErrors(
      "case class C(@opt(positional = true) v: Count = Count(0)) derives Parser.Command"
    )
    assert(e2.contains("cannot be used positionally"), e2)
    // Boolean has the explicit-value form, so Option[Boolean] stays legal: presence is Some(true)
    case class D(@opt dry: Option[Boolean] = None) derives Parser.Command
    assertEquals(ok(Flagged.parse[D](Seq("--dry"))), D(Some(true)))
    assertEquals(ok(Flagged.parse[D](Seq("--dry=false"))), D(Some(false)))
  }

  test("reserved names are compile errors when given as constants") {
    val e1 = compileErrors("case class C(@opt(short = 'h') x: Int = 0) derives Parser.Command")
    assert(e1.contains("short option 'h' is reserved for help"), e1)
    val e2 = compileErrors("case class C(@opt(name = \"help\") x: Int = 0) derives Parser.Command")
    assert(e2.contains("option name 'help' is reserved"), e2)
  }

  test("cross-field rules with static shapes are compile errors") {
    val e1 = compileErrors(
      "case class C(a: Trailing = Trailing(), b: Trailing = Trailing()) derives Parser.Command"
    )
    assert(e1.contains("only one trailing field is supported per command"), e1)
    val e2 = compileErrors(
      "case class C(xs: List[Int] = Nil, y: Int = 0) derives Parser.Command"
    )
    assert(e2.contains("a repeated positional must be the last positional field"), e2)
  }

  test("duplicate constant names are compile errors (union-membership as subtyping)") {
    val e1 = compileErrors(
      """case class C(@opt(short = 'v') a: Int = 0, @opt(short = 'v') b: Int = 0)
         derives Parser.Command"""
    )
    assert(e1.contains("duplicate short option"), e1)
    val e2 = compileErrors(
      """case class C(@opt(name = "x") a: Int = 0, @opt(name = "x") b: Int = 0)
         derives Parser.Command"""
    )
    assert(e2.contains("duplicate option name"), e2)
    // an option and a positional may share a name: separate namespaces
    case class Ok2(input: String = "-", @opt(name = "input") o: Int = 0) derives Parser.Command
    assertEquals(ok(Flagged.parse[Ok2](Seq("--input", "3", "f"))), Ok2("f", 3))
  }

  test("a Trailing field collects the raw arguments after --") {
    case class Exec(@opt(short = 'v') verbose: Boolean = false, rest: Trailing = Trailing())
        derives Parser.Command
    assertEquals(
      ok(Flagged.parse[Exec](Seq("-v", "--", "-x", "--weird", "--"))),
      Exec(verbose = true, rest = Trailing(Vector("-x", "--weird", "--")))
    )
    assertEquals(ok(Flagged.parse[Exec](Seq("-v"))), Exec(verbose = true))
  }

  test("Option[Trailing] distinguishes absent -- from present-but-empty") {
    case class Exec(@opt cmd: String = "sh", rest: Option[Trailing] = None) derives Parser.Command
    assertEquals(ok(Flagged.parse[Exec](Nil)).rest, None)
    assertEquals(ok(Flagged.parse[Exec](Seq("--"))).rest, Some(Trailing()))
    assertEquals(ok(Flagged.parse[Exec](Seq("--", "a"))).rest, Some(Trailing(Vector("a"))))
  }

  test("custom trailing parsers can require arguments") {
    case class Cmdline(parts: IndexedSeq[String])
    given Parser.Trailing[Cmdline] = Parser.trailing(l =>
      if l.isEmpty then Result.Err("expected a command after '--'") else Result.Ok(Cmdline(l))
    )
    case class Run(@opt image: String = "img", cmd: Cmdline) derives Parser.Command
    assertEquals(ok(Flagged.parse[Run](Seq("--", "echo", "hi"))).cmd, Cmdline(Vector("echo", "hi")))
    val m = err(Flagged.parse[Run](Nil))
    assert(m.contains("expected a command after '--'"), m)
  }

  test("without a trailing field, -- still escapes option parsing") {
    // (VarargPositionals behavior, asserted again next to the trailing tests)
    assertEquals(
      ok(Flagged.parse[VarargPositionals](Seq("a", "--", "-v"))),
      VarargPositionals(verbose = false, files = List("a", "-v"))
    )
  }

  test("trailing fields appear in usage and help") {
    case class Exec(@opt(help = "Command to run in the container") rest: Trailing = Trailing())
        derives Parser.Command
    Flagged.parse[Exec](Seq("--help")) match
      case Result.Err(ParseError.Help(t)) =>
        assert(t.contains("[-- <args>]"), t)
        assert(t.contains("Command to run in the container"), t)
      case other => fail(s"expected help, got $other")
  }

  test("unannotated fields are positional by default, like @main parameters") {
    case class MainLike(host: String, port: Int = 8080, secure: Boolean = false)
    val p = Parser.Command.derived[MainLike]
    assertEquals(
      ok(p.parse(Seq("example.com", "9000", "true"))),
      MainLike("example.com", 9000, true)
    )
    assertEquals(ok(p.parse(Seq("example.com"))), MainLike("example.com"))
  }

  test("a bare @opt makes a field a named option again") {
    case class Named2(file: String, @opt limit: Int = 10)
    val p = Parser.Command.derived[Named2]
    assertEquals(ok(p.parse(Seq("in.txt", "--limit", "3"))), Named2("in.txt", 3))
  }

  test("an unannotated pure flag is a compile error asking for @opt") {
    val e = compileErrors("case class C(count: Count = Count(0)) derives Parser.Command")
    assert(e.contains("add @opt to make it a named flag"), e)
  }
