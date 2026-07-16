package claw

import java.nio.file.Paths
import scala.concurrent.duration.*

enum LogLevel derives Parser.Enumerated:
  case Debug, Info, Warn, Error

case class ValueConfig(
    level: LogLevel = LogLevel.Info,
    path: java.nio.file.Path = Paths.get("."),
    timeout: FiniteDuration = 30.seconds,
    ratio: Double = 0.5,
    id: Option[java.util.UUID] = None
) derives Parser

class ValueParserSuite extends munit.FunSuite:

  def ok[A](r: ParseResult[A]): A = r match
    case Ok(a) => a
    case other => fail(s"expected success, got $other")

  test("by-name enum Parser parses by kebab-cased name") {
    assertEquals(summon[Parser[LogLevel]].read("warn"), Ok(LogLevel.Warn))
    assertEquals(summon[Parser[LogLevel]].read("DEBUG"), Ok(LogLevel.Debug))
    assert(summon[Parser[LogLevel]].read("nope").isErr)
    assertEquals(summon[Parser[LogLevel]].typeName, "debug|info|warn|error")
  }

  test("a by-name Parser given makes an enum field a value") {
    assertEquals(
      ok(Claw.parse[ValueConfig](Seq("--level", "error"))).level,
      LogLevel.Error
    )
  }

  test("built-in readers: path, duration, double, uuid") {
    val id = java.util.UUID.randomUUID()
    val cfg = ok(
      Claw.parse[ValueConfig](
        Seq("--path", "/tmp/x", "--timeout", "5s", "--ratio", "0.25", "--id", id.toString)
      )
    )
    assertEquals(cfg.path, Paths.get("/tmp/x"))
    assertEquals(cfg.timeout, 5.seconds)
    assertEquals(cfg.ratio, 0.25)
    assertEquals(cfg.id, Some(id))
  }

  test("reader typeName appears as metavar in help") {
    Claw.parse[ValueConfig](Seq("--help")) match
      case Err(ParseError.Help(t)) =>
        assert(t.contains("--path <path>"), t)
        assert(t.contains("--timeout <duration>"), t)
        assert(t.contains("--level <debug|info|warn|error>"), t)
      case other => fail(s"expected help, got $other")
  }

  test("map/emap combinators") {
    given portParser: Parser[Int] = Parser.of[Int]("port")(s =>
      s.toIntOption match
        case Some(p) if p > 0 && p < 65536 => Ok(p)
        case Some(_)                       => Err(s"'$s' out of range")
        case None                          => Err(s"'$s' is not a port")
    )
    case class Srv(port: Int = 80) derives Parser
    assertEquals(ok(Claw.parse[Srv](Seq("--port", "8080"))).port, 8080)
    Claw.parse[Srv](Seq("--port", "99999")) match
      case Err(ParseError.Failure(m, _)) => assert(m.contains("out of range"), m)
      case other                         => fail(s"expected failure, got $other")
  }

  test("boolean reader accepts unix-y spellings") {
    assertEquals(summon[Parser[Boolean]].read("on"), Ok(true))
    assertEquals(summon[Parser[Boolean]].read("0"), Ok(false))
    assert(summon[Parser[Boolean]].read("maybe").isErr)
  }

  test("flags can accumulate occurrences (counter)") {
    case class Verb(@short('v') verbose: Count = Count(0)) derives Parser
    assertEquals(ok(Claw.parse[Verb](Seq("-vvv"))).verbose, Count(3))
    assertEquals(ok(Claw.parse[Verb](Seq("--verbose", "--verbose"))).verbose, Count(2))
    assertEquals(ok(Claw.parse[Verb](Nil)).verbose, Count(0))
  }

  test("flag shape is pluggable via Parser.flag") {
    enum Volume:
      case Quiet, Loud
    given Parser[Volume] = Parser.flag(n => Ok(if n > 0 then Volume.Loud else Volume.Quiet))
    case class Player(@short('l') loud: Volume = Volume.Quiet) derives Parser
    assertEquals(ok(Claw.parse[Player](Seq("-l"))).loud, Volume.Loud)
    assertEquals(ok(Claw.parse[Player](Nil)).loud, Volume.Quiet)
    // no value parser: --loud=x is rejected
    Claw.parse[Player](Seq("--loud=x")) match
      case Err(ParseError.Failure(m, _)) => assert(m.contains("does not take a value"), m)
      case other                         => fail(s"expected failure, got $other")
  }

  test("a flag reader can bound the occurrence count") {
    case class Verbosity(n: Int)
    given Parser[Verbosity] = Parser.flag(n =>
      if n <= 3 then Ok(Verbosity(n)) else Err(s"at most 3 occurrences (got $n)")
    )
    case class C(@short('v') verbose: Verbosity = Verbosity(0)) derives Parser
    assertEquals(ok(Claw.parse[C](Seq("-vv"))).verbose, Verbosity(2))
    Claw.parse[C](Seq("-vvvv")) match
      case Err(ParseError.Failure(m, _)) => assert(m.contains("at most 3"), m)
      case other                         => fail(s"expected failure, got $other")
  }

  test("any type can opt into repeated shape via Parser.repeated") {
    given Parser[Set[String]] = Parser.repeated[String, Set[String]](l => Ok(l.toSet))
    case class Tags(tag: Set[String] = Set.empty) derives Parser
    assertEquals(
      ok(Claw.parse[Tags](Seq("--tag", "a", "--tag", "b", "--tag", "a"))),
      Tags(Set("a", "b"))
    )
    assertEquals(ok(Claw.parse[Tags](Nil)), Tags(Set.empty))
  }

  test("a repeated reader can require at least one occurrence") {
    case class AtLeastOne(xs: List[Int])
    given Parser[AtLeastOne] = Parser.repeated[Int, AtLeastOne](l =>
      if l.isEmpty then Err("expected at least one occurrence") else Ok(AtLeastOne(l))
    )
    case class Cfg(num: AtLeastOne) derives Parser
    assertEquals(ok(Claw.parse[Cfg](Seq("--num", "1", "--num", "2"))), Cfg(AtLeastOne(List(1, 2))))
    Claw.parse[Cfg](Nil) match
      case Err(ParseError.Failure(m, _)) =>
        assert(m.contains("--num") && m.contains("expected at least one"), m)
      case other => fail(s"expected failure, got $other")
  }

  test("emap composes over a repeated reader") {
    given Parser[Int] = Parser.of[Int]("int")(s => s.toIntOption.fold(Err(s"'$s' not an int"))(Ok(_)))
    given Parser[List[Int]] =
      Parser[List[Int]](using Parser.repeated[Int, List[Int]](l => Ok(l))).emap(l =>
        if l.sum > 10 then Err("sum too large") else Ok(l)
      )
    case class Sums(n: List[Int] = Nil) derives Parser
    assertEquals(ok(Claw.parse[Sums](Seq("--n", "1", "--n", "2"))), Sums(List(1, 2)))
    Claw.parse[Sums](Seq("--n", "9", "--n", "9")) match
      case Err(ParseError.Failure(m, _)) => assert(m.contains("sum too large"), m)
      case other                         => fail(s"expected failure, got $other")
  }
