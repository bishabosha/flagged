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

// ---- baseline comparisons -------------------------------------------------------

case class FWide25(
    opt1: Int = 0,
    opt2: Int = 0,
    opt3: Int = 0,
    opt4: Int = 0,
    opt5: Int = 0,
    opt6: Int = 0,
    opt7: Int = 0,
    opt8: Int = 0,
    opt9: Int = 0,
    opt10: Int = 0,
    opt11: Int = 0,
    opt12: Int = 0,
    opt13: Int = 0,
    opt14: Int = 0,
    opt15: Int = 0,
    opt16: Int = 0,
    opt17: Int = 0,
    opt18: Int = 0,
    opt19: Int = 0,
    opt20: Int = 0,
    opt21: Int = 0,
    opt22: Int = 0,
    opt23: Int = 0,
    opt24: Int = 0,
    opt25: Int = 0
) derives Parser.Command

/** The grammar `@main def run(foo: String, bar: Int, baz: Boolean, qux: String*)` expresses. */
case class FMainStyle(
    @positional foo: String,
    @positional bar: Int,
    @positional baz: Boolean,
    @positional qux: List[String] = Nil
) derives Parser.Command

object FlaggedDefs:
  val simple    = summon[Parser.Command[FSimple]]
  val verbosity = summon[Parser.Command[FVerbosity]]
  val withGroup = summon[Parser.Command[FWithGroup]]
  val nums      = summon[Parser.Command[FNums]]
  val defines   = summon[Parser.Command[FDefines]]
  val wide25    = summon[Parser.Command[FWide25]]
  val mainStyle = summon[Parser.Command[FMainStyle]]
