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
    opt1: String = "",
    opt2: String = "",
    opt3: String = "",
    opt4: String = "",
    opt5: String = "",
    opt6: String = "",
    opt7: String = "",
    opt8: String = "",
    opt9: String = "",
    opt10: String = "",
    opt11: String = "",
    opt12: String = "",
    opt13: String = "",
    opt14: String = "",
    opt15: String = "",
    opt16: String = "",
    opt17: String = "",
    opt18: String = "",
    opt19: String = "",
    opt20: String = "",
    opt21: String = "",
    opt22: String = "",
    opt23: String = "",
    opt24: String = "",
    opt25: String = ""
) derives Parser.Command

/** The grammar of an `@main` method with 25 `String` parameters. */
case class FPos25(
    @positional a1: String,
    @positional a2: String,
    @positional a3: String,
    @positional a4: String,
    @positional a5: String,
    @positional a6: String,
    @positional a7: String,
    @positional a8: String,
    @positional a9: String,
    @positional a10: String,
    @positional a11: String,
    @positional a12: String,
    @positional a13: String,
    @positional a14: String,
    @positional a15: String,
    @positional a16: String,
    @positional a17: String,
    @positional a18: String,
    @positional a19: String,
    @positional a20: String,
    @positional a21: String,
    @positional a22: String,
    @positional a23: String,
    @positional a24: String,
    @positional a25: String
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
  val pos25     = summon[Parser.Command[FPos25]]
