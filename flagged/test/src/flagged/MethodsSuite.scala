package flagged

object calc:
  @cmd
  def scale(@opt(short = 'n') num: Int, @opt factor: Int = 2, @opt verbose: Boolean = false): Int =
    if verbose then num * factor else num * factor

object toolbox:
  @cmd(help = "Adds two numbers")
  def add(@opt a: Int, @opt b: Int = 0): Int = a + b

  @cmd(name = "rm")
  def remove(path: String, @opt force: Boolean = false): String =
    if force then s"rm -f $path" else s"rm $path"

  @cmd
  object remote:
    @cmd
    def add(@opt name: String, @opt url: String = ""): String = s"remote:$name:$url"

    @cmd
    def prune(): String = "pruned"

  def helper(x: Int): Int = x // not @cmd: not a command

// a user-defined marker: annotations deriving from meta.Reflectable are mirrored, but only
// @cmd itself makes a member a flagged command
final case class marker() extends meta.Reflectable derives meta.Defaults

object customMarker:
  @marker def double(x: Int): Int = x * 2

object mixed:
  @cmd def go(@opt x: Int): Int  = x + 1
  @marker def other(y: Int): Int = y

object defaulted:
  @cmd(default = true)
  def status(@opt(short = 's') short: Boolean = false): String = if short then "st" else "status"

  @cmd
  def build(@opt target: String = "all"): String = s"build:$target"

object defaultedNested:
  @cmd(default = true)
  object grp:
    @cmd(default = true) def go(@opt x: Int = 3): Int = x
    @cmd def stop(): Int                              = -1

  @cmd def other(@opt y: Int = 1): Int = y

object guarded:
  var invocations = 0

  @cmd def go(@opt x: Int = 0): Int =
    invocations += 1
    x

  @cmd def noop(): Int = 0

class MethodsSuite extends munit.FunSuite:

  def ok[A](r: ParseResult[A]): A = r match
    case Result.Ok(a)                         => a
    case Result.Err(ParseError.Help(t))       => fail(s"expected success, got help:\n$t")
    case Result.Err(ParseError.Failure(m, _)) => fail(s"expected success, got failure: $m")

  def err[A](r: ParseResult[A]): String = r match
    case Result.Err(ParseError.Failure(m, _)) => m
    case other                                => fail(s"expected failure, got $other")

  val single = Parser.method(calc)
  val multi  = Parser.methods(toolbox)

  test("a single @cmd method parses its @opt parameters as options") {
    assertEquals(ok(single.parse(Seq("--num", "3", "--factor", "4"))), 12)
  }

  test("method default arguments fill absent options") {
    assertEquals(ok(single.parse(Seq("-n", "5"))), 10)
  }

  test("missing required method parameter is reported") {
    val m = err(single.parse(Nil))
    assert(m.contains("--num"), m)
  }

  test("invalid method parameter value is reported") {
    val m = err(single.parse(Seq("--num", "banana")))
    assert(m.contains("--num") && m.contains("banana"), m)
  }

  test("a group of @cmd methods parses as subcommands, kebab-named") {
    assertEquals(ok(multi.parse(Seq("add", "--a", "2", "--b", "3"))), 5)
    assertEquals(ok(multi.parse(Seq("add", "--a", "2"))), 2)
  }

  test("a grouped @cmd method is not invoked when an ancestor has parse errors") {
    guarded.invocations = 0
    val m = err(Flagged.parse[guarded.type](Seq("--bogus", "go")))
    assert(m.contains("unknown option '--bogus'"), m)
    assertEquals(guarded.invocations, 0)
  }

  test("parent errors prevent a selected subcommand from being parsed") {
    guarded.invocations = 0
    val m = err(Flagged.parse[guarded.type](Seq("--bogus", "go", "--x", "bad")))
    assert(m.contains("unknown option '--bogus'"), m)
    assert(!m.contains("invalid value for '--x'"), m)
    assertEquals(guarded.invocations, 0)
  }

  test("@cmd(name) renames a method command; unannotated parameters are positional") {
    assertEquals(ok(multi.parse(Seq("rm", "x.txt"))), "rm x.txt")
    assertEquals(ok(multi.parse(Seq("rm", "x.txt", "--force"))), "rm -f x.txt")
  }

  test("@cmd method parameters are positional by default, like @main") {
    object mainLike:
      @cmd def copy(src: String, dest: String, force: Boolean = false): String =
        s"$src->$dest${if force then "!" else ""}"
    assertEquals(ok(Parser.method(mainLike).parse(Seq("a.txt", "b.txt", "true"))), "a.txt->b.txt!")
  }

  test("nested @cmd objects become nested subcommands") {
    assertEquals(ok(multi.parse(Seq("remote", "add", "--name", "origin"))), "remote:origin:")
    assertEquals(ok(multi.parse(Seq("remote", "prune"))), "pruned")
  }

  test("un-annotated methods are not commands") {
    val m = err(multi.parse(Seq("helper", "--x", "1")))
    assert(m.contains("unknown command 'helper'"), m)
  }

  test("group help lists methods with @cmd(help) text") {
    multi.parse(Seq("--help")) match
      case Result.Err(ParseError.Help(t)) =>
        assert(t.contains("add") && t.contains("Adds two numbers"), t)
        assert(t.contains("rm"), t)
        assert(t.contains("remote"), t)
      case other => fail(s"expected help, got $other")
  }

  test("method help shows parameter options with defaults") {
    single.parse(Seq("--help")) match
      case Result.Err(ParseError.Help(t)) =>
        assert(t.contains("Usage: scale"), t)
        assert(t.contains("-n, --num <int>"), t)
        assert(t.contains("default: 2"), t)
      case other => fail(s"expected help, got $other")
  }

  test("the group result type is the union of the method results") {
    val r: Int | String = ok(multi.parse(Seq("add", "--a", "1")))
    assertEquals(r, 1)
  }

  test("Parser.method on an object with several @cmd methods is a compile error") {
    val e = compileErrors("Parser.method(toolbox)")
    assert(e.contains("exactly one @cmd method"), e)
  }

  test(
    "@cmd(default = true) names the method run when no command token is given, with args forwarded"
  ) {
    val p = Parser.methods(defaulted)
    assertEquals(ok(p.parse(Nil)), "status")
    assertEquals(ok(p.parse(Seq("-s"))), "st")
    assertEquals(ok(p.parse(Seq("--short"))), "st")
    assertEquals(ok(p.parse(Seq("build", "--target", "x"))), "build:x")
    val help = Flagged.help[defaulted.type]
    assert(help.contains("(default)"), help)
    assert(help.contains("[<command>]"), help)
  }

  test("@cmd(default = true) on a nested @cmd object defaults into that group") {
    val p = Parser.methods(defaultedNested)
    assertEquals(ok(p.parse(Nil)), 3)             // grp, then grp's own default
    assertEquals(ok(p.parse(Seq("--x", "9"))), 9) // forwarded through both levels
    assertEquals(ok(p.parse(Seq("grp", "stop"))), -1)
    assertEquals(ok(p.parse(Seq("other", "--y", "2"))), 2)
  }

  test("more than one @cmd(default = true) method is a compile error") {
    val e = compileErrors(
      "object o:\n" +
        "  @cmd(default = true) def a(x: Int = 0): Int = x\n" +
        "  @cmd(default = true) def b(y: Int = 1): Int = y\n" +
        "Parser.methods(o)"
    )
    assert(e.contains("only one @cmd(default = true) command is supported"), e)
  }

  test("a @cmd(default = true) method and nested object together are a compile error") {
    val e = compileErrors(
      "object o:\n" +
        "  @cmd(default = true) def a(x: Int = 0): Int = x\n" +
        "  @cmd(default = true) object g:\n" +
        "    @cmd def b(y: Int = 1): Int = y\n" +
        "Parser.methods(o)"
    )
    assert(e.contains("only one @cmd(default = true) command is supported"), e)
  }

  test("@cmd(default = true) on a lone @cmd method is a compile error") {
    val e = compileErrors(
      "object o:\n  @cmd(default = true) def only(x: Int = 0): Int = x\nParser.method(o)"
    )
    assert(e.contains("@cmd(default = true) has no effect on a single @cmd method"), e)
  }

  test("duplicate constant command names are a compile error") {
    val e = compileErrors(
      "object o:\n" +
        "  @cmd(name = \"x\") def a(p: Int = 0): Int = p\n" +
        "  @cmd(name = \"x\") def b(q: Int = 1): Int = q\n" +
        "Parser.methods(o)"
    )
    assert(e.contains("duplicate command name"), e)
  }

  test("an object nested in a class is rejected, not crashed on") {
    // Ref(mod) carries no prefix, so mirroring a per-instance object used to reach erasure and
    // fail an assertion there ("missing outer accessor"); it must be a diagnostic instead
    val e = compileErrors(
      "class Host:\n" +
        "  object cmds:\n" +
        "    @cmd def go(x: Int = 1): Int = x\n" +
        "val h = new Host\n" +
        "Parser.methods(h.cmds)"
    )
    // the mirror aborts inside implicit search, so only the summon failure is quoted back
    assert(e.contains("No given instance of type flagged.runner.MethodEntry"), e)
    assert(e.contains("macro expansion was stopped"), e)
  }

  test("a marker annotation on a non-@cmd member is ignored, like the member itself") {
    val e = compileErrors(
      "object o:\n" +
        "  @cmd(default = true) def a(x: Int = 0): Int = x\n" +
        "  @marker def b(y: Int = 1): Int = y\n" +
        "Parser.methods(o)"
    )
    assertEquals(e, "")
  }

  test("meta.Reflectable annotations are mirrored, but only @cmd makes a command") {
    val mm = summon[meta.MethodsMirror[customMarker.type]]
    assertEquals(mm.method(0).invoke(Array(4)), 8)
    val e = compileErrors("Flagged.parse[customMarker.type](Nil)")
    assert(e.nonEmpty, "expected a compile error for an object with no @cmd members")
  }

  test("the mirror is one level deep: method(i) throws on a Scope entry") {
    val mm = summon[meta.MethodsMirror[toolbox.type]]
    assertEquals(mm.method(0).invoke(Array(1, 2)), 3)
    // entry 2 is the nested `remote` object: its mirror must be summoned separately
    intercept[NoSuchElementException](mm.method(2))
    val rm = summon[meta.MethodsMirror[toolbox.remote.type]]
    assertEquals(rm.method(1).invoke(Array.empty[Any]), "pruned")
  }

  test("non-@cmd mirrored members are invisible to the parser") {
    // one @cmd method + one @marker method: the command view is a lone method, parsed flat
    assertEquals(ok(Flagged.parse[mixed.type](Seq("--x", "1"))), 2)
    assertEquals(ok(Parser.method(mixed).parse(Seq("--x", "3"))), 4)
  }

  test("Flagged.parse falls back to @cmd methods when no Parser exists") {
    assertEquals(ok(Flagged.parse[calc.type](Seq("-n", "3"))), 6)
    assertEquals(ok(Flagged.parse[toolbox.type](Seq("add", "--a", "2", "--b", "3"))), 5)
  }

  test("a Parser given takes precedence over @cmd derivation") {
    given Parser.Value[calc.type] = Parser.of("calc")(_ => Result.Ok(calc))
    assertEquals(ok(Flagged.parse[calc.type](Seq("anything"))), calc)
  }

  test("Flagged.help renders for a @cmd object") {
    val t = Flagged.help[toolbox.type]
    assert(t.contains("Usage: toolbox"), t)
    assert(t.contains("add") && t.contains("rm") && t.contains("remote"), t)
  }

  test("Flagged.parse accepts a prog override for a @cmd object") {
    Flagged.parse[calc.type](Seq("--help"), "myscale") match
      case Result.Err(ParseError.Help(t)) => assert(t.contains("Usage: myscale"), t)
      case other                          => fail(s"expected help, got $other")
  }

  test("Flagged.parseOrExit returns the invoked method's result") {
    assertEquals(Flagged.parseOrExit[calc.type](Seq("-n", "2")), 4)
    assertEquals(Flagged.parseOrExit[toolbox.type](Seq("rm", "a.txt")), "rm a.txt")
  }
