package flagged

case class OwnsHelpAll(
    @opt(name = "help-all") all: Boolean = false
) derives Parser.Command

class HelpSuite extends munit.FunSuite:

  def helpText[A](args: Seq[String])(using Parser[A]): String =
    Flagged.parse[A](args) match
      case Result.Err(ParseError.Help(t)) => t
      case other                          => fail(s"expected help, got $other")

  test("--help at top level") {
    val t = helpText[Git](Seq("--help"))
    assert(t.contains("A tiny git-like tool"), t)
    assert(t.contains("Usage: git [options] <command>"), t)
    assert(t.contains("clone") && t.contains("Clone a repository"), t)
    assert(t.contains("remote") && t.contains("Manage remotes"), t)
    assert(t.contains("status"), t)
    assert(t.contains("-h, --help"), t)
    assert(t.contains("Run 'git <command> --help'"), t)
  }

  test("-h is help") {
    assert(helpText[Git](Seq("-h")).contains("Usage: git"))
  }

  test("--help at subcommand level shows that command's options") {
    val t = helpText[Git](Seq("clone", "--help"))
    assert(t.contains("Clone a repository"), t)
    assert(t.contains("Usage: git clone [options] <repo>"), t)
    assert(t.contains("-d, --depth <int>"), t)
    assert(t.contains("-q, --quiet"), t)
    assert(t.contains("<repo>") && t.contains("Repository URL"), t)
  }

  test("--help at nested subcommand level") {
    val t = helpText[Git](Seq("remote", "--help"))
    assert(t.contains("Usage: git remote [options] <command>"), t)
    assert(t.contains("add") && t.contains("Add a remote"), t)
    assert(t.contains("remove"), t)

    val t2 = helpText[Git](Seq("remote", "add", "--help"))
    assert(t2.contains("Usage: git remote add [options] <name> <url>"), t2)
  }

  test("help shows defaults and required markers") {
    val t = helpText[Basic](Seq("--help"))
    assert(t.contains("default: out.txt"), t)
    assert(t.contains("default: 3"), t)

    val t2 = helpText[Required](Seq("--help"))
    assert(t2.contains("required"), t2)
    assert(t2.contains("default: 8080"), t2)
  }

  test("help shows enum value alternatives as metavar") {
    val t = helpText[Paint](Seq("--help"))
    assert(t.contains("<red|green|deep-blue>"), t)
  }

  test("help shows repeatable options") {
    val t = helpText[Collections](Seq("--help"))
    assert(t.contains("repeatable"), t)
  }

  test("help via Flagged.help without parsing") {
    val t = Flagged.help[Git]
    assert(t.contains("Usage: git"), t)
    assert(Flagged.help[Git]("mygit").contains("Usage: mygit"))
  }

  test("prog name defaults to kebab-cased type name") {
    val t = helpText[VarargPositionals](Seq("--help"))
    assert(t.contains("Usage: vararg-positionals"), t)
  }

  test("failure hints point at --help for the right level") {
    Flagged.parse[Git](Seq("clone")) match
      case Result.Err(ParseError.Failure(m, hint)) =>
        assert(m.contains("<repo>"), m)
        assert(hint.contains("git clone --help"), hint)
      case other => fail(s"expected failure, got $other")
  }

  test("--help-all reveals hidden options, marked; plain help only advertises the toggle") {
    val plain = helpText[ParityHidden](Seq("--help"))
    assert(plain.contains("--help-all"), plain)
    assert(!plain.contains("--secret"), plain)

    val full = helpText[ParityHidden](Seq("--help-all"))
    assert(full.contains("--secret"), full)
    assert(full.contains("default: b, hidden"), full)
  }

  test("--help-all reveals hidden subcommands, marked") {
    val t = helpText[ParityTool](Seq("--help-all"))
    assert(t.contains("debug"), t)
    assert(t.contains("(hidden)"), t)
  }

  test("--help-all is not advertised when nothing is hidden") {
    val t = helpText[Git](Seq("--help"))
    assert(!t.contains("--help-all"), t)
  }

  test("Flagged.helpAll renders the --help-all screen without parsing") {
    assert(Flagged.helpAll[ParityHidden].contains("--secret"))
    assert(!Flagged.help[ParityHidden].contains("--secret"))
  }

  test("a user option named help-all takes precedence over the toggle") {
    Flagged.parse[OwnsHelpAll](Seq("--help-all")) match
      case Result.Ok(v) => assertEquals(v, OwnsHelpAll(all = true))
      case other        => fail(s"expected the user option to parse, got $other")
  }
