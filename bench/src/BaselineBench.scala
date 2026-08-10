package bench

import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.*
import bench.defs.{BaselineDefs, FlaggedDefs}

/** Parse latency of non-library baselines, next to flagged on identical argument lists (the
  * `empty`/`simple`/`repeated`/`bundled` flagged rows live in [[RuntimeBench]]; same JMH settings,
  * one forked JVM per benchmark either way). `wide25` scales the typical hand-rolled idiom to 25
  * named options; `positional` compares Scala's built-in `@main` machinery against flagged parsing
  * the same tokens as all-`@opt(positional = true)` fields — the only grammar `@main` can express.
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
class BaselineBench:

  private val emptyArgs    = Seq.empty[String]
  private val simpleArgs   = Seq("--foo", "hello", "--bar", "42", "--baz")
  private val repeatedArgs = Seq("--qux", "a", "--qux", "b", "--qux", "c", "--qux", "d")
  private val bundledArgs  = Seq("-bfhello", "--bar", "7")
  private val posArgs      = Seq("hello", "42", "true")
  private val mainArgs     = posArgs.toArray
  private val wide25Args   = (1 to 25).flatMap(i => Seq(s"--opt-$i", s"v$i"))
  private val pos25Args    = (1 to 25).map(i => s"v$i")
  private val main25Args   = pos25Args.toArray
  private val emptyList    = emptyArgs.toList
  private val simpleList   = simpleArgs.toList
  private val repeatedList = repeatedArgs.toList
  private val wide25List   = wide25Args.toList

  @Setup
  def validate(): Unit =
    def fOk(r: flagged.ParseResult[?]): Boolean = r match
      case flagged.Result.Ok(_) => true
      case _                    => false
    val checks = Seq(
      "empty_hand"             -> BaselineDefs.naive(emptyList).isRight,
      "simple_hand"            -> BaselineDefs.naive(simpleList).isRight,
      "repeated_hand"          -> BaselineDefs.naive(repeatedList).isRight,
      "empty_handfull"         -> BaselineDefs.full(emptyArgs).isRight,
      "simple_handfull"        -> BaselineDefs.full(simpleArgs).isRight,
      "repeated_handfull"      -> BaselineDefs.full(repeatedArgs).isRight,
      "bundled_handfull"       -> BaselineDefs.full(bundledArgs).isRight,
      "wide25_hand"            -> BaselineDefs.naive25(wide25List).exists(_.opt25 == "v25"),
      "wide25_flagged"         -> fOk(FlaggedDefs.wide25.parse(wide25Args)),
      "positional_scalamain"   -> (BaselineDefs.scalaMain(mainArgs).bar == 42),
      "positional_flagged"     -> fOk(FlaggedDefs.mainStyle.parse(posArgs)),
      "positional25_scalamain" -> (BaselineDefs.scalaMain25(main25Args).opt25 == "v25"),
      "positional25_flagged"   -> fOk(FlaggedDefs.pos25.parse(pos25Args))
    )
    val failing = checks.collect { case (name, false) => name }
    if failing.nonEmpty then
      throw new IllegalStateException(s"scenarios do not parse: ${failing.mkString(", ")}")

  // the typical quick hand-rolled tailrec/match parser (long options only)
  @Benchmark def empty_hand: Any    = BaselineDefs.naive(emptyList)
  @Benchmark def simple_hand: Any   = BaselineDefs.naive(simpleList)
  @Benchmark def repeated_hand: Any = BaselineDefs.naive(repeatedList)

  // a hand-rolled parser at feature parity on this grammar
  @Benchmark def empty_handfull: Any    = BaselineDefs.full(emptyArgs)
  @Benchmark def simple_handfull: Any   = BaselineDefs.full(simpleArgs)
  @Benchmark def repeated_handfull: Any = BaselineDefs.full(repeatedArgs)
  @Benchmark def bundled_handfull: Any  = BaselineDefs.full(bundledArgs)

  // 25 named options, all provided: the match chain's per-token 25-field copy vs the engine
  @Benchmark def wide25_hand: Any    = BaselineDefs.naive25(wide25List)
  @Benchmark def wide25_flagged: Any = FlaggedDefs.wide25.parse(wide25Args)

  // Scala's @main machinery vs flagged on the same all-positional grammar and tokens
  @Benchmark def positional_scalamain: Any = BaselineDefs.scalaMain(mainArgs)
  @Benchmark def positional_flagged: Any   = FlaggedDefs.mainStyle.parse(posArgs)

  // the same comparison at 25 positional String parameters
  @Benchmark def positional25_scalamain: Any = BaselineDefs.scalaMain25(main25Args)
  @Benchmark def positional25_flagged: Any   = FlaggedDefs.pos25.parse(pos25Args)
