package claw


@help("A tiny git-like tool")
enum Git derives Parser:
  @help("Clone a repository")
  case Clone(
      @positional @help("Repository URL") repo: String,
      @short('d') @help("Clone depth") depth: Option[Int] = None,
      @short('q') quiet: Boolean = false
  )
  @help("Manage remotes")
  case Remote(action: RemoteAction)
  @help("Show status")
  case Status

enum RemoteAction derives Parser:
  @help("Add a remote")
  case Add(@positional name: String, @positional url: String)
  @help("Remove a remote")
  case Remove(@positional name: String)

enum SimpleCmd derives Parser:
  case Start
  case Stop

// enum used as an option *value*: opts in explicitly via derives Parser.Enumerated
enum Color derives Parser.Enumerated:
  case Red, Green, DeepBlue

case class Paint(color: Color = Color.Red, @positional what: String = "wall") derives Parser

// optional subcommand
case class Tool(
    @short('v') verbose: Boolean = false,
    action: Option[SimpleAction] = None
) derives Parser

enum SimpleAction derives Parser:
  case Run(@short('j') jobs: Int = 1)
  case Clean

// deliberately has no Parser instance: used to check the compile error
enum NoDerive:
  case Go(@positional x: Int)
  case Halt

class SubcommandSuite extends munit.FunSuite:

  def ok[A](r: ParseResult[A]): A = r match
    case Ok(a)                          => a
    case Err(ParseError.Help(t))        => fail(s"expected success, got help:\n$t")
    case Err(ParseError.Failure(m, _))  => fail(s"expected success, got failure: $m")

  def err[A](r: ParseResult[A]): String = r match
    case Err(ParseError.Failure(m, _)) => m
    case other                         => fail(s"expected failure, got $other")

  test("parameterless subcommand") {
    assertEquals(ok(Claw.parse[Git](Seq("status"))), Git.Status)
  }

  test("subcommand with options and positional") {
    assertEquals(
      ok(Claw.parse[Git](Seq("clone", "https://x.git", "--depth", "3", "-q"))),
      Git.Clone("https://x.git", Some(3), quiet = true)
    )
  }

  test("nested subcommands two levels deep") {
    assertEquals(
      ok(Claw.parse[Git](Seq("remote", "add", "origin", "https://x.git"))),
      Git.Remote(RemoteAction.Add("origin", "https://x.git"))
    )
    assertEquals(
      ok(Claw.parse[Git](Seq("remote", "remove", "origin"))),
      Git.Remote(RemoteAction.Remove("origin"))
    )
  }

  test("missing command") {
    val m = err(Claw.parse[Git](Nil))
    assert(m.contains("missing command"), m)
    assert(m.contains("clone") && m.contains("remote") && m.contains("status"), m)
  }

  test("unknown command with suggestion") {
    val m = err(Claw.parse[Git](Seq("clonee")))
    assert(m.contains("unknown command 'clonee'"), m)
    assert(m.contains("did you mean 'clone'"), m)
  }

  test("missing nested command") {
    val m = err(Claw.parse[Git](Seq("remote")))
    assert(m.contains("missing command"), m)
    assert(m.contains("add") && m.contains("remove"), m)
  }

  test("simple enum derives subcommands at top level") {
    assertEquals(ok(Claw.parse[SimpleCmd](Seq("start"))), SimpleCmd.Start)
    assertEquals(ok(Claw.parse[SimpleCmd](Seq("stop"))), SimpleCmd.Stop)
  }

  test("an enum field with a by-name Parser parses as a value") {
    assertEquals(ok(Claw.parse[Paint](Seq("--color", "green"))), Paint(Color.Green))
    assertEquals(ok(Claw.parse[Paint](Seq("--color", "deep-blue", "fence"))), Paint(Color.DeepBlue, "fence"))
  }

  test("enum value is matched case-insensitively and errors list alternatives") {
    assertEquals(ok(Claw.parse[Paint](Seq("--color", "RED"))), Paint(Color.Red))
    val m = err(Claw.parse[Paint](Seq("--color", "mauve")))
    assert(m.contains("red, green, deep-blue"), m)
  }

  test("optional subcommand: absent") {
    assertEquals(ok(Claw.parse[Tool](Seq("-v"))), Tool(verbose = true, action = None))
  }

  test("optional subcommand: present") {
    assertEquals(
      ok(Claw.parse[Tool](Seq("run", "--jobs", "4"))),
      Tool(action = Some(SimpleAction.Run(4)))
    )
  }

  test("parent options must come before the subcommand") {
    assertEquals(
      ok(Claw.parse[Tool](Seq("-v", "clean"))),
      Tool(verbose = true, action = Some(SimpleAction.Clean))
    )
    // after the subcommand, -v belongs to the subcommand and is unknown there
    val m = err(Claw.parse[Tool](Seq("clean", "-v")))
    assert(m.contains("unknown option '-v'"), m)
  }

  test("subcommand result via sealed trait") {
    sealed trait Op derives Parser
    object Op:
      case class Add(@positional x: Int, @positional y: Int) extends Op
      case object Noop extends Op
    assertEquals(Claw.parse[Op](Seq("add", "1", "2"))(using summon[Parser[Op]]), Ok(Op.Add(1, 2)))
    assertEquals(Claw.parse[Op](Seq("noop"))(using summon[Parser[Op]]), Ok(Op.Noop))
  }

  test("derivation reuses a Parser given in scope for a subcommand field") {
    // hand-modified parser for RemoteAction: every command name gets an "x-" prefix
    val base = Parser.derived[RemoteAction].command
    val renamed = base.sub.get.copy(cases = base.sub.get.cases.map(c => c.copy(name = s"x-${c.name}")))
    given custom: Parser[RemoteAction] = Parser.make(base.copy(sub = Some(renamed)), "remote-action")

    case class Wrap(action: RemoteAction) derives Parser
    // the derived Wrap parser must embed the custom instance, not re-derive structurally
    assertEquals(
      ok(Claw.parse[Wrap](Seq("x-add", "origin", "https://x.git"))),
      Wrap(RemoteAction.Add("origin", "https://x.git"))
    )
    val m = err(Claw.parse[Wrap](Seq("add", "origin", "https://x.git")))
    assert(m.contains("unknown command 'add'"), m)
  }

  test("a nested subcommand enum without its own Parser instance is a compile error") {
    val errors = compileErrors("case class Root(action: NoDerive) derives Parser")
    assert(errors.contains("No given Parser[claw.NoDerive] found"), errors)
    assert(errors.contains("derives Parser"), errors)
  }

  test("structural misconfiguration reported when the parser is constructed") {
    case class Dup(@name("x") a: Int = 0, @name("x") b: Int = 0)
    val e = intercept[IllegalArgumentException](Parser.derived[Dup])
    assert(e.getMessage.contains("duplicate option name '--x'"), e.getMessage)
  }
