package flagged

import java.nio.file.Paths

// java.nio.file.Path is available on the JVM and Scala Native, not on Scala.js
case class PathConfig(
    @opt path: java.nio.file.Path = Paths.get(".")
) derives Parser.Command

class PathSuite extends munit.FunSuite:

  test("built-in path reader") {
    Flagged.parse[PathConfig](Seq("--path", "/tmp/x")) match
      case Result.Ok(cfg) => assertEquals(cfg.path, Paths.get("/tmp/x"))
      case other          => fail(s"expected success, got $other")
  }

  test("path metavar appears in help") {
    Flagged.parse[PathConfig](Seq("--help")) match
      case Result.Err(ParseError.Help(t)) => assert(t.contains("--path <path>"), t)
      case other                          => fail(s"expected help, got $other")
  }
