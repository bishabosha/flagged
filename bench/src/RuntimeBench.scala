package bench

import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.*
import bench.defs.{FlaggedDefs, MainargsDefs, CaseappDefs}

/** Parse latency for identical command lines across the three libraries, against parsers built once
  * in setup (derivation cost is the compile-time benchmark's subject, construction cost is excluded
  * for all three alike). Add `-prof gc` to also measure allocation per parse
  * (`gc.alloc.rate.norm`).
  *
  * Scenario groups:
  *   - `empty` / `simple` / `repeated` — expressible in all three libraries
  *   - `bundled` / `leftover` — flagged and mainargs only (case-app has no short clusters or typed
  *     leftover)
  *   - `counter` / `group` — flagged and case-app only (mainargs has no counters or `@Recurse`
  *     equivalent beyond class splicing, which the `simple` scenario already covers)
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
class RuntimeBench:

  private val emptyArgs    = Seq.empty[String]
  private val simpleArgs   = Seq("--foo", "hello", "--bar", "42", "--baz")
  private val repeatedArgs = Seq("--qux", "a", "--qux", "b", "--qux", "c", "--qux", "d")
  private val bundledArgs  = Seq("-bfhello", "--bar", "7")
  private val counterArgs  = Seq("-v", "-v", "-v", "--target", "x")
  private val groupArgs    = Seq("--host", "h", "--port", "8080", "-q", "--log-level", "warn")
  // option first: mainargs treats everything from the first leftover token on as leftover
  private val leftoverArgs = Seq("-s", "2", "1", "2", "3", "4", "5")

  /** A failed parse can be faster than a successful one; assert every scenario succeeds. */
  @Setup
  def validate(): Unit =
    def fOk(r: flagged.ParseResult[?]): Boolean = r match
      case flagged.Ok(_) => true
      case _             => false
    def mOk(r: mainargs.Result[?]): Boolean = r match
      case mainargs.Result.Success(_) => true
      case _                          => false
    val checks = Seq(
      "empty_flagged"     -> fOk(FlaggedDefs.simple.parse(emptyArgs)),
      "empty_mainargs"    -> mOk(MainargsDefs.simple.constructRaw(emptyArgs)),
      "empty_caseapp"     -> CaseappDefs.simple.detailedParse(emptyArgs).isRight,
      "simple_flagged"    -> fOk(FlaggedDefs.simple.parse(simpleArgs)),
      "simple_mainargs"   -> mOk(MainargsDefs.simple.constructRaw(simpleArgs)),
      "simple_caseapp"    -> CaseappDefs.simple.detailedParse(simpleArgs).isRight,
      "repeated_flagged"  -> fOk(FlaggedDefs.simple.parse(repeatedArgs)),
      "repeated_mainargs" -> mOk(MainargsDefs.simple.constructRaw(repeatedArgs)),
      "repeated_caseapp"  -> CaseappDefs.simple.detailedParse(repeatedArgs).isRight,
      "bundled_flagged"   -> fOk(FlaggedDefs.simple.parse(bundledArgs)),
      "bundled_mainargs"  -> mOk(MainargsDefs.simple.constructRaw(bundledArgs)),
      "counter_flagged"   -> fOk(FlaggedDefs.verbosity.parse(counterArgs)),
      "counter_caseapp"   -> CaseappDefs.verbosity.detailedParse(counterArgs).isRight,
      "group_flagged"     -> fOk(FlaggedDefs.withGroup.parse(groupArgs)),
      "group_caseapp"     -> CaseappDefs.withGroup.detailedParse(groupArgs).isRight,
      "leftover_flagged"  -> fOk(FlaggedDefs.nums.parse(leftoverArgs)),
      "leftover_mainargs" -> mOk(MainargsDefs.nums.constructRaw(leftoverArgs))
    )
    val failing = checks.collect { case (name, false) => name }
    if failing.nonEmpty then
      throw new IllegalStateException(s"scenarios do not parse: ${failing.mkString(", ")}")

  // ---- all three ----------------------------------------------------------------

  @Benchmark def empty_flagged: Any  = FlaggedDefs.simple.parse(emptyArgs)
  @Benchmark def empty_mainargs: Any = MainargsDefs.simple.constructRaw(emptyArgs)
  @Benchmark def empty_caseapp: Any  = CaseappDefs.simple.detailedParse(emptyArgs)

  @Benchmark def simple_flagged: Any  = FlaggedDefs.simple.parse(simpleArgs)
  @Benchmark def simple_mainargs: Any = MainargsDefs.simple.constructRaw(simpleArgs)
  @Benchmark def simple_caseapp: Any  = CaseappDefs.simple.detailedParse(simpleArgs)

  @Benchmark def repeated_flagged: Any  = FlaggedDefs.simple.parse(repeatedArgs)
  @Benchmark def repeated_mainargs: Any = MainargsDefs.simple.constructRaw(repeatedArgs)
  @Benchmark def repeated_caseapp: Any  = CaseappDefs.simple.detailedParse(repeatedArgs)

  // ---- flagged x mainargs ---------------------------------------------------------

  @Benchmark def bundled_flagged: Any  = FlaggedDefs.simple.parse(bundledArgs)
  @Benchmark def bundled_mainargs: Any = MainargsDefs.simple.constructRaw(bundledArgs)

  @Benchmark def leftover_flagged: Any  = FlaggedDefs.nums.parse(leftoverArgs)
  @Benchmark def leftover_mainargs: Any = MainargsDefs.nums.constructRaw(leftoverArgs)

  // ---- flagged x case-app ---------------------------------------------------------

  @Benchmark def counter_flagged: Any = FlaggedDefs.verbosity.parse(counterArgs)
  @Benchmark def counter_caseapp: Any = CaseappDefs.verbosity.detailedParse(counterArgs)

  @Benchmark def group_flagged: Any = FlaggedDefs.withGroup.parse(groupArgs)
  @Benchmark def group_caseapp: Any = CaseappDefs.withGroup.detailedParse(groupArgs)
