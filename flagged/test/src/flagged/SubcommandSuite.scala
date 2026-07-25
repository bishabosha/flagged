package flagged

@help("A tiny git-like tool")
enum Git derives Parser.CommandGroup:
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

enum RemoteAction derives Parser.CommandGroup:
  @help("Add a remote")
  case Add(@positional name: String, @positional url: String)
  @help("Remove a remote")
  case Remove(@positional name: String)

enum SimpleCmd derives Parser.CommandGroup:
  case Start
  case Stop

// enum used as an option *value*: opts in explicitly via derives Parser.Enumerated
enum Color derives Parser.Enumerated:
  case Red, Green, DeepBlue

case class Paint(color: Color = Color.Red, @positional what: String = "wall") derives Parser.Command

// optional subcommand
case class Tool(
    @short('v') verbose: Boolean = false,
    action: Option[SimpleAction] = None
) derives Parser.Command

enum SimpleAction derives Parser.CommandGroup:
  case Run(@short('j') jobs: Int = 1)
  case Clean

// deliberately has no Parser instance: used to check the compile error
enum NoDerive:
  case Go(@positional x: Int)
  case Halt

class SubcommandSuite extends munit.FunSuite:

  def ok[A](r: ParseResult[A]): A = r match
    case Ok(a)                         => a
    case Err(ParseError.Help(t))       => fail(s"expected success, got help:\n$t")
    case Err(ParseError.Failure(m, _)) => fail(s"expected success, got failure: $m")

  def err[A](r: ParseResult[A]): String = r match
    case Err(ParseError.Failure(m, _)) => m
    case other                         => fail(s"expected failure, got $other")

  test("parameterless subcommand") {
    assertEquals(ok(Flagged.parse[Git](Seq("status"))), Git.Status)
  }

  test("subcommand with options and positional") {
    assertEquals(
      ok(Flagged.parse[Git](Seq("clone", "https://x.git", "--depth", "3", "-q"))),
      Git.Clone("https://x.git", Some(3), quiet = true)
    )
  }

  test("nested subcommands two levels deep") {
    assertEquals(
      ok(Flagged.parse[Git](Seq("remote", "add", "origin", "https://x.git"))),
      Git.Remote(RemoteAction.Add("origin", "https://x.git"))
    )
    assertEquals(
      ok(Flagged.parse[Git](Seq("remote", "remove", "origin"))),
      Git.Remote(RemoteAction.Remove("origin"))
    )
  }

  test("missing command") {
    val m = err(Flagged.parse[Git](Nil))
    assert(m.contains("missing command"), m)
    assert(m.contains("clone") && m.contains("remote") && m.contains("status"), m)
  }

  test("unknown command with suggestion") {
    val m = err(Flagged.parse[Git](Seq("clonee")))
    assert(m.contains("unknown command 'clonee'"), m)
    assert(m.contains("did you mean 'clone'"), m)
  }

  test("missing nested command") {
    val m = err(Flagged.parse[Git](Seq("remote")))
    assert(m.contains("missing command"), m)
    assert(m.contains("add") && m.contains("remove"), m)
  }

  test("simple enum derives subcommands at top level") {
    assertEquals(ok(Flagged.parse[SimpleCmd](Seq("start"))), SimpleCmd.Start)
    assertEquals(ok(Flagged.parse[SimpleCmd](Seq("stop"))), SimpleCmd.Stop)
  }

  test("an enum field with a by-name Parser parses as a value") {
    assertEquals(ok(Flagged.parse[Paint](Seq("--color", "green"))), Paint(Color.Green))
    assertEquals(
      ok(Flagged.parse[Paint](Seq("--color", "deep-blue", "fence"))),
      Paint(Color.DeepBlue, "fence")
    )
  }

  test("enum value is matched exactly (case-sensitive) and errors list alternatives") {
    val m = err(Flagged.parse[Paint](Seq("--color", "RED")))
    assert(m.contains("red, green, deep-blue"), m)
    val m2 = err(Flagged.parse[Paint](Seq("--color", "mauve")))
    assert(m2.contains("red, green, deep-blue"), m2)
  }

  test("optional subcommand: absent") {
    assertEquals(ok(Flagged.parse[Tool](Seq("-v"))), Tool(verbose = true, action = None))
  }

  test("optional subcommand: present") {
    assertEquals(
      ok(Flagged.parse[Tool](Seq("run", "--jobs", "4"))),
      Tool(action = Some(SimpleAction.Run(4)))
    )
  }

  test("parent options must come before the subcommand") {
    assertEquals(
      ok(Flagged.parse[Tool](Seq("-v", "clean"))),
      Tool(verbose = true, action = Some(SimpleAction.Clean))
    )
    // after the subcommand, -v belongs to the subcommand and is unknown there
    val m = err(Flagged.parse[Tool](Seq("clean", "-v")))
    assert(m.contains("unknown option '-v'"), m)
  }

  test("subcommand result via sealed trait") {
    sealed trait Op derives Parser.CommandGroup
    object Op:
      case class Add(@positional x: Int, @positional y: Int) extends Op
      case object Noop                                       extends Op
    assertEquals(
      Flagged.parse[Op](Seq("add", "1", "2")),
      Ok(Op.Add(1, 2))
    )
    assertEquals(Flagged.parse[Op](Seq("noop")), Ok(Op.Noop))
  }

  test("derivation reuses a Parser given in scope for a subcommand field") {
    // hand-modified parser for RemoteAction: every command name gets an "x-" prefix
    val base    = Parser.CommandGroup.derived[RemoteAction].command
    val renamed =
      base.sub.get.copy(cases = base.sub.get.cases.map(c => c.copy(name = s"x-${c.name}")))
    given custom: Parser.CommandGroup[RemoteAction] =
      Parser.makeGroup(base.copy(sub = Some(renamed)), "remote-action")

    case class Wrap(action: RemoteAction) derives Parser.Command
    // the derived Wrap parser must embed the custom instance, not re-derive structurally
    assertEquals(
      ok(Flagged.parse[Wrap](Seq("x-add", "origin", "https://x.git"))),
      Wrap(RemoteAction.Add("origin", "https://x.git"))
    )
    val m = err(Flagged.parse[Wrap](Seq("add", "origin", "https://x.git")))
    assert(m.contains("unknown command 'add'"), m)
  }

  test("a nested subcommand enum without its own Parser instance is a compile error") {
    val errors = compileErrors("case class Root(action: NoDerive) derives Parser.Command")
    assert(errors.contains("No given Parser[flagged.NoDerive] found"), errors)
    assert(errors.contains("derives Parser.Command"), errors)
  }

  test("structural misconfiguration reported when the parser is constructed") {
    // a label-derived name collides only after kebab-casing, which is value-level:
    // this stays a construction-time error (constant @name collisions are static now)
    case class Dup(maxRetries: Int = 0, @name("max-retries") b: Int = 0)
    val e = intercept[IllegalArgumentException](Parser.Command.derived[Dup])
    assert(e.getMessage.contains("duplicate option name '--max-retries'"), e.getMessage)
  }

  test("construction validates an invalid unselected subcommand") {
    enum Tree:
      case Good
      case Bad(maxRetries: Int = 0, @name("max-retries") retryLimit: Int = 0)

    val e = intercept[IllegalArgumentException](Parser.CommandGroup.derived[Tree])
    assert(e.getMessage.contains("duplicate option name '--max-retries'"), e.getMessage)
  }

  test("parse indexes are prepared only along the selected command path") {
    enum Tree:
      case Left(@short('x') x: Int = 0)
      case Right(@short('y') y: Int = 0)

    val parser = Parser.CommandGroup.derived[Tree]
    val root   = parser.impl
    val cases  = root.sub.get.cases
    assert(!root.isPrepared)
    assert(cases.forall(c => !c.command.isPrepared))

    assertEquals(parser.parse(Seq("left", "--x", "2")), Ok(Tree.Left(2)))
    assert(root.isPrepared)
    assert(cases(0).command.isPrepared)
    assert(!cases(1).command.isPrepared)
  }

  test("duplicate constant command names are compile errors") {
    val e = compileErrors(
      "enum E derives Parser.CommandGroup:\n  @name(\"x\") case A\n  @name(\"x\") case B(f: Int)"
    )
    assert(e.contains("duplicate command name"), e)
    // an alias colliding with another case's primary name is also constant
    val e2 = compileErrors(
      "enum E derives Parser.CommandGroup:\n  @name(\"a\") @name(\"b\") case A\n  @name(\"b\") case B(f: Int)"
    )
    assert(e2.contains("duplicate command name"), e2)
  }

  test("a kebab-derived command-name collision is a construction error") {
    // FooBar's derived name only becomes "foo-bar" after kebab-casing: value-level
    enum Clash:
      case FooBar
      @name("foo-bar") case Other(f: Int)
    val e = intercept[IllegalArgumentException](Parser.CommandGroup.derived[Clash])
    assert(e.getMessage.contains("duplicate command name 'foo-bar'"), e.getMessage)
  }

  test("more than one @default command is a compile error") {
    val e = compileErrors(
      "enum E derives Parser.CommandGroup:\n  @default case A\n  @default case B(f: Int)"
    )
    assert(e.contains("only one @default command is supported"), e)
  }
