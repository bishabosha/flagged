package flagged

import scala.util.CommandLineParser.FromString

/** A `@main`-style custom value type: its [[FromString]] instance predates the migration. */
case class Port(value: Int)
object Port:
  given FromString[Port]:
    def fromString(s: String) =
      val n = s.toInt // NumberFormatException is an IllegalArgumentException
      if n > 0 && n < 65536 then Port(n)
      else throw IllegalArgumentException(s"'$s' is not a valid port")

object migratedServe:
  @cmd def serve(host: String, port: Port = Port(80)): String = s"$host:${port.value}"

/** The `@main` migration bridge: `FromString` instances embed as low-priority value parsers. */
class MainMigrationSuite extends munit.FunSuite:

  def ok[A](r: ParseResult[A]): A = r match
    case Result.Ok(a) => a
    case other        => fail(s"expected success, got $other")

  def err[A](r: ParseResult[A]): String = r match
    case Result.Err(ParseError.Failure(m, _)) => m
    case other                                => fail(s"expected failure, got $other")

  test("a FromString instance parses a positional field, like @main") {
    case class Serve(host: String, port: Port) derives Parser.Command
    assertEquals(
      ok(Flagged.parse[Serve](Seq("localhost", "8080"))),
      Serve("localhost", Port(8080))
    )
  }

  test("a FromString instance parses a named option") {
    case class Serve(@opt port: Port = Port(80)) derives Parser.Command
    assertEquals(ok(Flagged.parse[Serve](Seq("--port", "8080"))).port, Port(8080))
  }

  test("a FromString-typed @cmd method parameter parses") {
    assertEquals(
      ok(Flagged.parse[migratedServe.type](Seq("example.com", "8080"))),
      "example.com:8080"
    )
  }

  test("vararg migration: a collection field repeats the FromString element") {
    case class Scan(ports: Seq[Port] = Nil) derives Parser.Command
    assertEquals(ok(Flagged.parse[Scan](Seq("80", "443"))).ports, Seq(Port(80), Port(443)))
    assertEquals(ok(Flagged.parse[Scan](Nil)).ports, Nil)
  }

  test("the IllegalArgumentException message is the parse error") {
    case class Serve(@opt port: Port = Port(80)) derives Parser.Command
    assert(err(Flagged.parse[Serve](Seq("--port", "99999"))).contains("is not a valid port"))
  }

  test("a message-less IllegalArgumentException reports the token") {
    case class Token(s: String)
    given FromString[Token]:
      def fromString(s: String) = throw IllegalArgumentException()
    case class C(@opt t: Token = Token("")) derives Parser.Command
    assert(err(Flagged.parse[C](Seq("--t", "x"))).contains("'x' cannot be parsed"))
  }

  test("built-in instances beat the stdlib FromString instances") {
    // FromString[Int] exists in the stdlib; flagged's Value[Int] must win (its error style shows)
    case class C(@opt n: Int = 0) derives Parser.Command
    assert(err(Flagged.parse[C](Seq("--n", "abc"))).contains("'abc' is not a valid int"))
  }

  test("Boolean keeps its flag shape despite FromString[Boolean]") {
    case class C(@opt verbose: Boolean = false) derives Parser.Command
    assertEquals(ok(Flagged.parse[C](Seq("--verbose"))).verbose, true)
    assertEquals(ok(Flagged.parse[C](Nil)).verbose, false)
  }

  test("a dedicated Parser.Value beats the bridge") {
    case class Lvl(n: Int)
    given FromString[Lvl]:
      def fromString(s: String) = Lvl(-1)
    given Parser.Value[Lvl] = Parser.of("level")(s => Result.Ok(Lvl(s.length)))
    case class C(@opt l: Lvl = Lvl(0)) derives Parser.Command
    assertEquals(ok(Flagged.parse[C](Seq("--l", "abc"))).l, Lvl(3))
  }

  test("the bridge's metavar is <value>") {
    case class Serve(@opt port: Port = Port(80)) derives Parser.Command
    Flagged.parse[Serve](Seq("--help")) match
      case Result.Err(ParseError.Help(t)) => assert(t.contains("--port <value>"), t)
      case other                          => fail(s"expected help, got $other")
  }
