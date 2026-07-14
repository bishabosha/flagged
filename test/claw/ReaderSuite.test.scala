package claw

import claw.Result.*
import java.nio.file.Paths
import scala.concurrent.duration.*

enum LogLevel derives Reader:
  case Debug, Info, Warn, Error

case class ReaderConfig(
    level: LogLevel = LogLevel.Info,
    path: java.nio.file.Path = Paths.get("."),
    timeout: FiniteDuration = 30.seconds,
    ratio: Double = 0.5,
    id: Option[java.util.UUID] = None
) derives Parser

class ReaderSuite extends munit.FunSuite:

  def ok[A](r: Result[A]): A = r match
    case Ok(a)         => a
    case other         => fail(s"expected success, got $other")

  test("derived enum Reader parses by kebab-cased name") {
    assertEquals(summon[Reader[LogLevel]].read("warn"), Right(LogLevel.Warn))
    assertEquals(summon[Reader[LogLevel]].read("DEBUG"), Right(LogLevel.Debug))
    assert(summon[Reader[LogLevel]].read("nope").isLeft)
    assertEquals(summon[Reader[LogLevel]].typeName, "debug|info|warn|error")
  }

  test("a user-provided Reader beats subcommand interpretation for enum fields") {
    assertEquals(
      ok(Claw.parse[ReaderConfig](Seq("--level", "error"))).level,
      LogLevel.Error
    )
  }

  test("built-in readers: path, duration, double, uuid") {
    val id = java.util.UUID.randomUUID()
    val cfg = ok(
      Claw.parse[ReaderConfig](
        Seq("--path", "/tmp/x", "--timeout", "5s", "--ratio", "0.25", "--id", id.toString)
      )
    )
    assertEquals(cfg.path, Paths.get("/tmp/x"))
    assertEquals(cfg.timeout, 5.seconds)
    assertEquals(cfg.ratio, 0.25)
    assertEquals(cfg.id, Some(id))
  }

  test("reader typeName appears as metavar in help") {
    Claw.parse[ReaderConfig](Seq("--help")) match
      case Help(t) =>
        assert(t.contains("--path <path>"), t)
        assert(t.contains("--timeout <duration>"), t)
        assert(t.contains("--level <debug|info|warn|error>"), t)
      case other => fail(s"expected help, got $other")
  }

  test("map/emap combinators") {
    given portReader: Reader[Int] = Reader.of[Int]("port")(s =>
      s.toIntOption.toRight(s"'$s' is not a port").filterOrElse(p => p > 0 && p < 65536, s"'$s' out of range")
    )
    case class Srv(port: Int = 80) derives Parser
    assertEquals(ok(Claw.parse[Srv](Seq("--port", "8080"))).port, 8080)
    Claw.parse[Srv](Seq("--port", "99999")) match
      case Failure(m, _) => assert(m.contains("out of range"), m)
      case other         => fail(s"expected failure, got $other")
  }

  test("boolean reader accepts unix-y spellings") {
    assertEquals(summon[Reader[Boolean]].read("on"), Right(true))
    assertEquals(summon[Reader[Boolean]].read("0"), Right(false))
    assert(summon[Reader[Boolean]].read("maybe").isLeft)
  }
