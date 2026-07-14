package claw

class HelpSuite extends munit.FunSuite:

  def helpText[A](args: Seq[String])(using Parser[A]): String =
    Claw.parse[A](args) match
      case Err(ParseError.Help(t)) => t
      case other                   => fail(s"expected help, got $other")

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

  test("help via Claw.help without parsing") {
    val t = Claw.help[Git]
    assert(t.contains("Usage: git"), t)
    assert(Claw.help[Git]("mygit").contains("Usage: mygit"))
  }

  test("prog name defaults to kebab-cased type name") {
    val t = helpText[VarargPositionals](Seq("--help"))
    assert(t.contains("Usage: vararg-positionals"), t)
  }

  test("failure hints point at --help for the right level") {
    Claw.parse[Git](Seq("clone")) match
      case Err(ParseError.Failure(m, hint)) =>
        assert(m.contains("<repo>"), m)
        assert(hint.contains("git clone --help"), hint)
      case other => fail(s"expected failure, got $other")
  }
