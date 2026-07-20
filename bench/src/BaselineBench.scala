package bench

import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.*
import bench.defs.BaselineDefs

/** Parse latency of non-library baselines on the `simple` grammar, for comparison with the flagged
  * rows of [[RuntimeBench]] (same argument lists, same JMH settings, one forked JVM per benchmark
  * either way). `scalamain` parses the same data positionally — Scala's built-in `@main` machinery
  * has no named options — so it is a floor, not a like-for-like parser.
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
  private val mainArgs     = Array("hello", "42", "true")

  @Setup
  def validate(): Unit =
    val checks = Seq(
      "empty_hand"        -> BaselineDefs.naive(emptyArgs).isRight,
      "simple_hand"       -> BaselineDefs.naive(simpleArgs).isRight,
      "repeated_hand"     -> BaselineDefs.naive(repeatedArgs).isRight,
      "empty_handfull"    -> BaselineDefs.full(emptyArgs).isRight,
      "simple_handfull"   -> BaselineDefs.full(simpleArgs).isRight,
      "repeated_handfull" -> BaselineDefs.full(repeatedArgs).isRight,
      "bundled_handfull"  -> BaselineDefs.full(bundledArgs).isRight,
      "simple_scalamain"  -> (BaselineDefs.scalaMain(mainArgs).bar == 42)
    )
    val failing = checks.collect { case (name, false) => name }
    if failing.nonEmpty then
      throw new IllegalStateException(s"scenarios do not parse: ${failing.mkString(", ")}")

  // the typical quick hand-rolled loop (long options only)
  @Benchmark def empty_hand: Any    = BaselineDefs.naive(emptyArgs)
  @Benchmark def simple_hand: Any   = BaselineDefs.naive(simpleArgs)
  @Benchmark def repeated_hand: Any = BaselineDefs.naive(repeatedArgs)

  // a hand-rolled parser at feature parity on this grammar
  @Benchmark def empty_handfull: Any    = BaselineDefs.full(emptyArgs)
  @Benchmark def simple_handfull: Any   = BaselineDefs.full(simpleArgs)
  @Benchmark def repeated_handfull: Any = BaselineDefs.full(repeatedArgs)
  @Benchmark def bundled_handfull: Any  = BaselineDefs.full(bundledArgs)

  // Scala's @main machinery, parsing the same data positionally
  @Benchmark def simple_scalamain: Any = BaselineDefs.scalaMain(mainArgs)
