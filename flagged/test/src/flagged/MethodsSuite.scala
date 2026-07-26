package flagged

object calc:
  @run
  def scale(@short('n') num: Int, factor: Int = 2, verbose: Boolean = false): Int =
    if verbose then num * factor else num * factor

object toolbox:
  @run
  @help("Adds two numbers")
  def add(a: Int, b: Int = 0): Int = a + b

  @run
  @name("rm")
  def remove(@positional path: String, force: Boolean = false): String =
    if force then s"rm -f $path" else s"rm $path"

  @run
  object remote:
    @run
    def add(name: String, url: String = ""): String = s"remote:$name:$url"

    @run
    def prune(): String = "pruned"

  def helper(x: Int): Int = x // not @run: not a command

// a user-defined marker: annotations deriving from meta.Reflectable are mirrored, but only
// @run itself makes a member a flagged command
final case class cmd() extends meta.Reflectable derives meta.Defaults

object customMarker:
  @cmd def double(x: Int): Int = x * 2

object mixed:
  @run def go(x: Int): Int    = x + 1
  @cmd def other(y: Int): Int = y

object defaulted:
  @run
  @default
  def status(@short('s') short: Boolean = false): String = if short then "st" else "status"

  @run
  def build(target: String = "all"): String = s"build:$target"

object defaultedNested:
  @run
  @default
  object grp:
    @run @default def go(x: Int = 3): Int = x
    @run def stop(): Int                  = -1

  @run def other(y: Int = 1): Int = y

object guarded:
  var invocations = 0

  @run def go(x: Int = 0): Int =
    invocations += 1
    x

  @run def noop(): Int = 0

class MethodsSuite extends munit.FunSuite:

  def ok[A](r: ParseResult[A]): A = r match
    case Ok(a)                         => a
    case Err(ParseError.Help(t))       => fail(s"expected success, got help:\n$t")
    case Err(ParseError.Failure(m, _)) => fail(s"expected success, got failure: $m")

  def err[A](r: ParseResult[A]): String = r match
    case Err(ParseError.Failure(m, _)) => m
    case other                         => fail(s"expected failure, got $other")

  val single = Parser.method(calc)
  val multi  = Parser.methods(toolbox)

  test("a single @run method parses its parameters as options") {
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

  test("a group of @run methods parses as subcommands, kebab-named") {
    assertEquals(ok(multi.parse(Seq("add", "--a", "2", "--b", "3"))), 5)
    assertEquals(ok(multi.parse(Seq("add", "--a", "2"))), 2)
  }

  test("a grouped @run method is not invoked when an ancestor has parse errors") {
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

  test("@name renames a method command; @positional works on parameters") {
    assertEquals(ok(multi.parse(Seq("rm", "x.txt"))), "rm x.txt")
    assertEquals(ok(multi.parse(Seq("rm", "x.txt", "--force"))), "rm -f x.txt")
  }

  test("nested @run objects become nested subcommands") {
    assertEquals(ok(multi.parse(Seq("remote", "add", "--name", "origin"))), "remote:origin:")
    assertEquals(ok(multi.parse(Seq("remote", "prune"))), "pruned")
  }

  test("un-annotated methods are not commands") {
    val m = err(multi.parse(Seq("helper", "--x", "1")))
    assert(m.contains("unknown command 'helper'"), m)
  }

  test("group help lists methods with @help text") {
    multi.parse(Seq("--help")) match
      case Err(ParseError.Help(t)) =>
        assert(t.contains("add") && t.contains("Adds two numbers"), t)
        assert(t.contains("rm"), t)
        assert(t.contains("remote"), t)
      case other => fail(s"expected help, got $other")
  }

  test("method help shows parameter options with defaults") {
    single.parse(Seq("--help")) match
      case Err(ParseError.Help(t)) =>
        assert(t.contains("Usage: scale"), t)
        assert(t.contains("-n, --num <int>"), t)
        assert(t.contains("default: 2"), t)
      case other => fail(s"expected help, got $other")
  }

  test("the group result type is the union of the method results") {
    val r: Int | String = ok(multi.parse(Seq("add", "--a", "1")))
    assertEquals(r, 1)
  }

  test("Parser.method on an object with several @run methods is a compile error") {
    val e = compileErrors("Parser.method(toolbox)")
    assert(e.contains("exactly one @run method"), e)
  }

  test("@default names the method run when no command token is given, with args forwarded") {
    val p = Parser.methods(defaulted)
    assertEquals(ok(p.parse(Nil)), "status")
    assertEquals(ok(p.parse(Seq("-s"))), "st")
    assertEquals(ok(p.parse(Seq("--short"))), "st")
    assertEquals(ok(p.parse(Seq("build", "--target", "x"))), "build:x")
    val help = Flagged.help[defaulted.type]
    assert(help.contains("(default)"), help)
    assert(help.contains("[<command>]"), help)
  }

  test("@default on a nested @run object defaults into that group") {
    val p = Parser.methods(defaultedNested)
    assertEquals(ok(p.parse(Nil)), 3)             // grp, then grp's own @default
    assertEquals(ok(p.parse(Seq("--x", "9"))), 9) // forwarded through both levels
    assertEquals(ok(p.parse(Seq("grp", "stop"))), -1)
    assertEquals(ok(p.parse(Seq("other", "--y", "2"))), 2)
  }

  test("more than one @default method is a compile error") {
    val e = compileErrors(
      "object o:\n" +
        "  @run @default def a(x: Int = 0): Int = x\n" +
        "  @run @default def b(y: Int = 1): Int = y\n" +
        "Parser.methods(o)"
    )
    assert(e.contains("only one @default command is supported"), e)
  }

  test("a @default method and a @default nested object together are a compile error") {
    val e = compileErrors(
      "object o:\n" +
        "  @run @default def a(x: Int = 0): Int = x\n" +
        "  @run @default object g:\n" +
        "    @run def b(y: Int = 1): Int = y\n" +
        "Parser.methods(o)"
    )
    assert(e.contains("only one @default command is supported"), e)
  }

  test("@default on a lone @run method is a compile error") {
    val e = compileErrors(
      "object o:\n  @run @default def only(x: Int = 0): Int = x\nParser.method(o)"
    )
    assert(e.contains("@default has no effect on a single @run method"), e)
  }

  test("@default on a non-@run member is ignored, like the member itself") {
    val e = compileErrors(
      "object o:\n" +
        "  @run @default def a(x: Int = 0): Int = x\n" +
        "  @cmd @default def b(y: Int = 1): Int = y\n" +
        "Parser.methods(o)"
    )
    assertEquals(e, "")
  }

  test("meta.Reflectable annotations are mirrored, but only @run makes a command") {
    val mm = summon[meta.MethodsMirror[customMarker.type]]
    assertEquals(mm.method(0).invoke(customMarker, Array(4)), 8)
    val e = compileErrors("Flagged.parse[customMarker.type](Nil)")
    assert(e.nonEmpty, "expected a compile error for an object with no @run members")
  }

  test("the mirror is one level deep: method(i) throws on a Scope entry") {
    val mm = summon[meta.MethodsMirror[toolbox.type]]
    assertEquals(mm.method(0).invoke(toolbox, Array(1, 2)), 3)
    // entry 2 is the nested `remote` object: its mirror must be summoned separately
    intercept[NoSuchElementException](mm.method(2))
    val rm = summon[meta.MethodsMirror[toolbox.remote.type]]
    assertEquals(rm.method(1).invoke(toolbox.remote, Array.empty[Any]), "pruned")
  }

  test("non-@run mirrored members are invisible to the parser") {
    // one @run method + one @cmd method: the run-view is a lone method, parsed flat
    assertEquals(ok(Flagged.parse[mixed.type](Seq("--x", "1"))), 2)
    assertEquals(ok(Parser.method(mixed).parse(Seq("--x", "3"))), 4)
  }

  test("Flagged.parse falls back to @run methods when no Parser exists") {
    assertEquals(ok(Flagged.parse[calc.type](Seq("-n", "3"))), 6)
    assertEquals(ok(Flagged.parse[toolbox.type](Seq("add", "--a", "2", "--b", "3"))), 5)
  }

  test("a Parser given takes precedence over @run derivation") {
    given Parser.Value[calc.type] = Parser.of("calc")(_ => Ok(calc))
    assertEquals(ok(Flagged.parse[calc.type](Seq("anything"))), calc)
  }

  test("Flagged.help renders for an @run object") {
    val t = Flagged.help[toolbox.type]
    assert(t.contains("Usage: toolbox"), t)
    assert(t.contains("add") && t.contains("rm") && t.contains("remote"), t)
  }

  test("Flagged.parse accepts a prog override for an @run object") {
    Flagged.parse[calc.type](Seq("--help"), "myscale") match
      case Err(ParseError.Help(t)) => assert(t.contains("Usage: myscale"), t)
      case other                   => fail(s"expected help, got $other")
  }

  test("Flagged.parseOrExit returns the invoked method's result") {
    assertEquals(Flagged.parseOrExit[calc.type](Seq("-n", "2")), 4)
    assertEquals(Flagged.parseOrExit[toolbox.type](Seq("rm", "a.txt")), "rm a.txt")
  }
