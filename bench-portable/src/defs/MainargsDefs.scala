package bench.defs

import mainargs.{main, arg, Flag, Leftover, ParserForClass}

// ---- three-way scenarios ------------------------------------------------------

@main case class MSimple(
    @arg(short = 'f') foo: String = "x",
    bar: Int = 0,
    @arg(short = 'b') baz: Flag = Flag(),
    qux: Seq[String] = Nil
)

// ---- flagged x mainargs -------------------------------------------------------

@main case class MNums(
    @arg(short = 's') scale: Int = 1,
    nums: Leftover[Int]
)

@main case class MDefines(
    @arg(short = 'D') define: Map[String, Int] = Map.empty
)

object MainargsDefs:
  val simple  = ParserForClass[MSimple]
  val nums    = ParserForClass[MNums]
  val defines = ParserForClass[MDefines]
