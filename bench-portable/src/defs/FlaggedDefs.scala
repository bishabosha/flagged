package bench.defs

import flagged.*

// ---- three-way scenarios ------------------------------------------------------

case class FSimple(
    @opt(short = 'f') foo: String = "x",
    @opt bar: Int = 0,
    @opt(short = 'b') baz: Boolean = false,
    @opt qux: List[String] = Nil
) derives Parser.Command

// ---- flagged x case-app -------------------------------------------------------

case class FVerbosity(
    @opt(short = 'v') verbose: Count = Count(0),
    @opt target: String = "all"
) derives Parser.Command

case class FLog(
    @opt(short = 'q') quiet: Boolean = false,
    logLevel: String = "info"
) derives Parser.Shared

case class FWithGroup(
    @opt host: String = "localhost",
    @opt port: Int = 80,
    log: FLog = FLog()
) derives Parser.Command

// ---- flagged x mainargs -------------------------------------------------------

case class FNums(
    @opt(short = 's') scale: Int = 1,
    nums: List[Int] = Nil
) derives Parser.Command

case class FDefines(
    @opt(short = 'D') define: Map[String, Int] = Map.empty
) derives Parser.Command

// ---- baseline comparisons -------------------------------------------------------

case class FWide25(
    @opt opt1: String = "",
    @opt opt2: String = "",
    @opt opt3: String = "",
    @opt opt4: String = "",
    @opt opt5: String = "",
    @opt opt6: String = "",
    @opt opt7: String = "",
    @opt opt8: String = "",
    @opt opt9: String = "",
    @opt opt10: String = "",
    @opt opt11: String = "",
    @opt opt12: String = "",
    @opt opt13: String = "",
    @opt opt14: String = "",
    @opt opt15: String = "",
    @opt opt16: String = "",
    @opt opt17: String = "",
    @opt opt18: String = "",
    @opt opt19: String = "",
    @opt opt20: String = "",
    @opt opt21: String = "",
    @opt opt22: String = "",
    @opt opt23: String = "",
    @opt opt24: String = "",
    @opt opt25: String = ""
) derives Parser.Command

/** The grammar of an `@main` method with 25 `String` parameters. */
case class FPos25(
    a1: String,
    a2: String,
    a3: String,
    a4: String,
    a5: String,
    a6: String,
    a7: String,
    a8: String,
    a9: String,
    a10: String,
    a11: String,
    a12: String,
    a13: String,
    a14: String,
    a15: String,
    a16: String,
    a17: String,
    a18: String,
    a19: String,
    a20: String,
    a21: String,
    a22: String,
    a23: String,
    a24: String,
    a25: String
) derives Parser.Command

/** The grammar `@main def run(foo: String, bar: Int, baz: Boolean, qux: String*)` expresses. */
case class FMainStyle(
    foo: String,
    bar: Int,
    baz: Boolean,
    qux: List[String] = Nil
) derives Parser.Command

object FlaggedDefs:
  val simple    = summon[Parser.Command[FSimple]]
  val verbosity = summon[Parser.Command[FVerbosity]]
  val withGroup = summon[Parser.Command[FWithGroup]]
  val nums      = summon[Parser.Command[FNums]]
  val defines   = summon[Parser.Command[FDefines]]
  val wide25    = summon[Parser.Command[FWide25]]
  val mainStyle = summon[Parser.Command[FMainStyle]]
  val pos25     = summon[Parser.Command[FPos25]]
