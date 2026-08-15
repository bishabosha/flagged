package flagged

import java.time.{Instant, LocalDate}

// java.time value instances exist only on the JVM (see PathSuite for java.nio.file.Path)
case class JvmConfig(
    @opt day: Option[LocalDate] = None,
    @opt at: Option[Instant] = None
) derives Parser.Command

class PlatformJvmSuite extends munit.FunSuite:

  def ok[A](r: ParseResult[A]): A = r match
    case Result.Ok(a) => a
    case other        => fail(s"expected success, got $other")

  test("built-in readers: date-time types") {
    val cfg = ok(
      Flagged.parse[JvmConfig](Seq("--day", "2026-07-20", "--at", "2026-07-20T12:00:00Z"))
    )
    assertEquals(cfg.day, Some(LocalDate.of(2026, 7, 20)))
    assertEquals(cfg.at, Some(Instant.parse("2026-07-20T12:00:00Z")))
  }

  test("invalid path and date values are reported with the reader's type name") {
    Flagged.parse[JvmConfig](Seq("--day", "not-a-date")) match
      case Result.Err(ParseError.Failure(m, _)) => assert(m.contains("not a valid date"), m)
      case other                                => fail(s"expected failure, got $other")
  }

  test("date metavar appears in help") {
    Flagged.parse[JvmConfig](Seq("--help")) match
      case Result.Err(ParseError.Help(t)) => assert(t.contains("--day <date>"), t)
      case other                          => fail(s"expected help, got $other")
  }
