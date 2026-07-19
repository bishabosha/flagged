package bench.defs

import caseapp.*

// ---- three-way scenarios ------------------------------------------------------

final case class CSimple(
    @ExtraName("f") foo: String = "x",
    bar: Int = 0,
    @ExtraName("b") baz: Boolean = false,
    qux: List[String] = Nil
)

// ---- flagged x case-app -------------------------------------------------------

final case class CVerbosity(
    @ExtraName("v") verbose: Int @@ Counter = Tag.of(0),
    target: String = "all"
)

final case class CLog(
    @ExtraName("q") quiet: Boolean = false,
    logLevel: String = "info"
)

final case class CWithGroup(
    host: String = "localhost",
    port: Int = 80,
    @Recurse log: CLog = CLog()
)

object CaseappDefs:
  val simple: Parser[CSimple]       = Parser[CSimple]
  val verbosity: Parser[CVerbosity] = Parser[CVerbosity]
  val withGroup: Parser[CWithGroup] = Parser[CWithGroup]
