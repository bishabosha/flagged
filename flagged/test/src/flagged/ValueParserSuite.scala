package flagged

import scala.concurrent.duration.*

enum LogLevel derives Parser.Enumerated:
  case Debug, Info, Warn, Error

case class ValueConfig(
    level: LogLevel = LogLevel.Info,
    timeout: FiniteDuration = 30.seconds,
    ratio: Double = 0.5,
    id: Option[java.util.UUID] = None
) derives Parser.Command

class ValueParserSuite extends munit.FunSuite:

  def ok[A](r: ParseResult[A]): A = r match
    case Ok(a) => a
    case other => fail(s"expected success, got $other")

  /** Parse a single token through the engine's slot protocol, boxed for assertions. */
  def read[A](s: String)(using p: Parser.SingleToken[A]): Result[A, String] =
    val out = new Array[Any](1)
    p.readInto(s, out, 0).map(_ => out(0).asInstanceOf[A])

  test("by-name enum Parser parses by kebab-cased name") {
    assertEquals(read[LogLevel]("warn"), Ok(LogLevel.Warn))
    assert(read[LogLevel]("DEBUG").isErr) // exact match, like clap/click/argmatch
    assert(read[LogLevel]("nope").isErr)
    assertEquals(summon[Parser[LogLevel]].typeName, "debug|info|warn|error")
  }

  test("a by-name Parser given makes an enum field a value") {
    assertEquals(
      ok(Flagged.parse[ValueConfig](Seq("--level", "error"))).level,
      LogLevel.Error
    )
  }

  test("built-in readers: duration, double, uuid") {
    val id  = java.util.UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
    val cfg = ok(
      Flagged.parse[ValueConfig](
        Seq("--timeout", "5s", "--ratio", "0.25", "--id", id.toString)
      )
    )
    assertEquals(cfg.timeout, 5.seconds)
    assertEquals(cfg.ratio, 0.25)
    assertEquals(cfg.id, Some(id))
  }

  test("negative infinity is accepted as a separate or positional numeric value") {
    assertEquals(
      ok(Flagged.parse[ValueConfig](Seq("--ratio", "-Infinity"))).ratio,
      Double.NegativeInfinity
    )
    case class Infinite(@positional value: Double) derives Parser.Command
    assertEquals(
      ok(Flagged.parse[Infinite](Seq("-Infinity"))),
      Infinite(Double.NegativeInfinity)
    )
  }

  test("reader typeName appears as metavar in help") {
    Flagged.parse[ValueConfig](Seq("--help")) match
      case Err(ParseError.Help(t)) =>
        assert(t.contains("--timeout <duration>"), t)
        assert(t.contains("--level <debug|info|warn|error>"), t)
      case other => fail(s"expected help, got $other")
  }

  test("map/emap combinators") {
    given portParser: Parser.Value[Int] = Parser.of[Int]("port")(s =>
      s.toIntOption match
        case Some(p) if p > 0 && p < 65536 => Ok(p)
        case Some(_)                       => Err(s"'$s' out of range")
        case None                          => Err(s"'$s' is not a port")
    )
    case class Srv(port: Int = 80) derives Parser.Command
    assertEquals(ok(Flagged.parse[Srv](Seq("--port", "8080"))).port, 8080)
    Flagged.parse[Srv](Seq("--port", "99999")) match
      case Err(ParseError.Failure(m, _)) => assert(m.contains("out of range"), m)
      case other                         => fail(s"expected failure, got $other")
  }

  test("boolean reader accepts unix-y spellings") {
    assertEquals(read[Boolean]("on"), Ok(true))
    assertEquals(read[Boolean]("0"), Ok(false))
    assert(read[Boolean]("maybe").isErr)
  }

  test("flags can accumulate occurrences (counter)") {
    case class Verb(@short('v') verbose: Count = Count(0)) derives Parser.Command
    assertEquals(ok(Flagged.parse[Verb](Seq("-vvv"))).verbose, Count(3))
    assertEquals(ok(Flagged.parse[Verb](Seq("--verbose", "--verbose"))).verbose, Count(2))
    assertEquals(ok(Flagged.parse[Verb](Nil)).verbose, Count(0))
  }

  test("flag shape is pluggable via Parser.flag") {
    enum Volume:
      case Quiet, Loud
    given Parser.Flag[Volume] =
      Parser.flag(n => Ok(if n > 0 then Volume.Loud else Volume.Quiet))
    case class Player(@short('l') loud: Volume = Volume.Quiet) derives Parser.Command
    assertEquals(ok(Flagged.parse[Player](Seq("-l"))).loud, Volume.Loud)
    assertEquals(ok(Flagged.parse[Player](Nil)).loud, Volume.Quiet)
    // no value parser: --loud=x is rejected
    Flagged.parse[Player](Seq("--loud=x")) match
      case Err(ParseError.Failure(m, _)) => assert(m.contains("does not take a value"), m)
      case other                         => fail(s"expected failure, got $other")
  }

  test("a flag reader can bound the occurrence count") {
    case class Verbosity(n: Int)
    given Parser.Flag[Verbosity] =
      Parser.flag(n => if n <= 3 then Ok(Verbosity(n)) else Err(s"at most 3 occurrences (got $n)"))
    case class C(@short('v') verbose: Verbosity = Verbosity(0)) derives Parser.Command
    assertEquals(ok(Flagged.parse[C](Seq("-vv"))).verbose, Verbosity(2))
    Flagged.parse[C](Seq("-vvvv")) match
      case Err(ParseError.Failure(m, _)) => assert(m.contains("at most 3"), m)
      case other                         => fail(s"expected failure, got $other")
  }

  test("any type can opt into repeated shape via Parser.repeated") {
    given Parser.Repeated[Set[String]] =
      Parser.repeated[String, Set[String]](l => Ok(l.toSet))
    case class Tags(tag: Set[String] = Set.empty) derives Parser.Command
    assertEquals(
      ok(Flagged.parse[Tags](Seq("--tag", "a", "--tag", "b", "--tag", "a"))),
      Tags(Set("a", "b"))
    )
    assertEquals(ok(Flagged.parse[Tags](Nil)), Tags(Set.empty))
  }

  test("a repeated reader can require at least one occurrence") {
    case class AtLeastOne(xs: List[Int])
    given Parser.Repeated[AtLeastOne] = Parser.repeated[Int, AtLeastOne](l =>
      if l.isEmpty then Err("expected at least one occurrence") else Ok(AtLeastOne(l.toList))
    )
    case class Cfg(num: AtLeastOne) derives Parser.Command
    assertEquals(
      ok(Flagged.parse[Cfg](Seq("--num", "1", "--num", "2"))),
      Cfg(AtLeastOne(List(1, 2)))
    )
    Flagged.parse[Cfg](Nil) match
      case Err(ParseError.Failure(m, _)) =>
        assert(m.contains("--num") && m.contains("expected at least one"), m)
      case other => fail(s"expected failure, got $other")
  }

  test("emap composes over a repeated reader") {
    given Parser.Value[Int] =
      Parser.of[Int]("int")(s => s.toIntOption.fold(Err(s"'$s' not an int"))(Ok(_)))
    given Parser.Repeated[List[Int]] =
      Parser
        .repeated[Int, List[Int]](l => Ok(l.toList))
        .emap(l => if l.sum > 10 then Err("sum too large") else Ok(l))
    case class Sums(n: List[Int] = Nil) derives Parser.Command
    assertEquals(ok(Flagged.parse[Sums](Seq("--n", "1", "--n", "2"))), Sums(List(1, 2)))
    Flagged.parse[Sums](Seq("--n", "9", "--n", "9")) match
      case Err(ParseError.Failure(m, _)) => assert(m.contains("sum too large"), m)
      case other                         => fail(s"expected failure, got $other")
  }
