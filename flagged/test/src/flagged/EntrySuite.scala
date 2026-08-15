package flagged

case class EntryServe(
    @opt(short = 'p') port: Int = 8080,
    @opt host: String = "localhost"
) derives Parser.Command

class EntrySuite extends munit.FunSuite:

  def ok[A](r: ParseResult[A]): A = r match
    case Result.Ok(a)                         => a
    case Result.Err(ParseError.Help(t))       => fail(s"expected success, got help:\n$t")
    case Result.Err(ParseError.Failure(m, _)) => fail(s"expected success, got failure: $m")

  def err[A](r: ParseResult[A]): String = r match
    case Result.Err(ParseError.Failure(m, _)) => m
    case other                                => fail(s"expected failure, got $other")

  test("entry parses like the one-shot methods") {
    val cli = Flagged.entry[EntryServe]
    assertEquals(ok(cli.parse(Seq("--port", "9000"))), EntryServe(port = 9000))
    assertEquals(
      ok(cli.parse(Seq("-p", "1", "--host", "h"))),
      ok(Flagged.parse[EntryServe](Seq("-p", "1", "--host", "h")))
    )
    assert(err(cli.parse(Seq("--port", "x"))).contains("int"))
  }

  test("one entry point holds one parser across parses") {
    val cli = Flagged.entry[toolbox.type]
    assert(cli.parser eq cli.parser)
    assertEquals(ok(cli.parse(Seq("add", "--a", "2", "--b", "40"))), 42)
    assertEquals(ok(cli.parse(Seq("rm", "file.txt"))), "rm file.txt")
  }

  test("parseOrExit returns the parsed value on success") {
    val cli = Flagged.entry[EntryServe]
    assertEquals(cli.parseOrExit(Seq("--host", "example.org")), EntryServe(host = "example.org"))
    assertEquals(Flagged.entry[toolbox.type].parseOrExit(Seq("add", "--a", "1")), 1)
  }

  test("prog overrides rename usage, help, and errors") {
    val cli = Flagged.entry[EntryServe]
    assert(cli.help("serve").contains("Usage: serve"))
    cli.parse(Seq("--help"), "serve") match
      case Result.Err(ParseError.Help(t)) => assert(t.contains("Usage: serve"))
      case other                          => fail(s"expected help, got $other")
  }

  test("help and helpAll render from the cached parser") {
    val cli = Flagged.entry[ParityHidden]
    assertEquals(cli.help, Flagged.help[ParityHidden])
    assert(cli.helpAll.contains("--secret"))
    assert(!cli.help.contains("--secret"))
  }
