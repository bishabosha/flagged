package bench.defs

import flagged.*

// ---- three-way scenarios ------------------------------------------------------

case class FSimple(
    @short('f') foo: String = "x",
    bar: Int = 0,
    @short('b') baz: Boolean = false,
    qux: List[String] = Nil
) derives Parser.Command

// ---- flagged x case-app -------------------------------------------------------

case class FVerbosity(
    @short('v') verbose: Count = Count(0),
    target: String = "all"
) derives Parser.Command

case class FLog(
    @short('q') quiet: Boolean = false,
    logLevel: String = "info"
) derives Parser.Command

case class FWithGroup(
    host: String = "localhost",
    port: Int = 80,
    log: FLog = FLog()
) derives Parser.Command

// ---- flagged x mainargs -------------------------------------------------------

case class FNums(
    @short('s') scale: Int = 1,
    @positional nums: List[Int] = Nil
) derives Parser.Command

case class FDefines(
    @short('D') define: Map[String, Int] = Map.empty
) derives Parser.Command

object FlaggedDefs:
  val simple    = summon[Parser.Command[FSimple]]
  val verbosity = summon[Parser.Command[FVerbosity]]
  val withGroup = summon[Parser.Command[FWithGroup]]
  val nums      = summon[Parser.Command[FNums]]
  val defines   = summon[Parser.Command[FDefines]]
