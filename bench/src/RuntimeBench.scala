package bench

import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.*
import bench.defs.{FlaggedDefs, MainargsDefs, CaseappDefs, RealisticDefs, RealisticJvmDefs}

/** Parse latency for identical command lines across the three libraries, against parsers built once
  * in setup (derivation cost is the compile-time benchmark's subject; the runtime cost of
  * *constructing* the parser value is excluded for all three alike and measured separately by
  * [[ConstructBench]]). Add `-prof gc` to also measure allocation per parse (`gc.alloc.rate.norm`).
  *
  * Scenario groups:
  *   - `empty` / `simple` / `repeated` — expressible in all three libraries
  *   - `bundled` / `leftover` / `map` — flagged and mainargs only (case-app has no short clusters,
  *     typed leftover, or `Map[K,V]`)
  *   - `counter` / `group` — flagged and case-app only (mainargs has no counters or `@Recurse`
  *     equivalent beyond class splicing, which the `simple` scenario already covers)
  *   - `realistic` — a docker-style CLI (one subcommand level, one large command; see
  *     [[bench.defs.RealisticDefs]]), each library in its idiomatic subcommand encoding
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
  private val mapArgs      = Seq("--define", "a=1", "--define", "b=2", "--define", "c=3")
  // options before positionals: mainargs' Leftover consumes everything after the image token
  private val realisticArgs = Seq(
    "run", "--name", "web", "-e", "PGHOST=db", "-e", "PGPORT=5432", "-p", "8080:80", "-p",
    "8443:443", "-v", "/srv/site:/usr/share/nginx/html:ro", "--label", "app=web", "--label",
    "env=prod", "--workdir", "/app", "--user", "1000:1000", "--memory", "512m", "--cpus", "1.5",
    "--restart", "on-failure", "--network", "bridge", "--detach", "--rm", "--read-only",
    "nginx:1.27", "nginx-debug"
  )
  private val realisticArr = realisticArgs.toArray

  /** A failed parse can be faster than a successful one; assert every scenario succeeds. */
  @Setup
  def validate(): Unit =
    def fOk(r: flagged.ParseResult[?]): Boolean = r match
      case flagged.Result.Ok(_) => true
      case _                    => false
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
      "leftover_mainargs" -> mOk(MainargsDefs.nums.constructRaw(leftoverArgs)),
      "map_flagged"       -> fOk(FlaggedDefs.defines.parse(mapArgs)),
      "map_mainargs"      -> mOk(MainargsDefs.defines.constructRaw(mapArgs)),
      // every library parses and agrees on every field, not just succeeds
      "realistic"         -> RealisticDefs.agrees(realisticArgs),
      "realistic_scopt"   -> RealisticJvmDefs.scoptAgrees(realisticArgs),
      "realistic_scallop" -> RealisticJvmDefs.scallopAgrees(realisticArgs),
      "realistic_picocli" -> RealisticJvmDefs.picocliAgrees(realisticArr)
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

  @Benchmark def realistic_flagged: Any  = RealisticDefs.flaggedDocker.parse(realisticArgs)
  @Benchmark def realistic_mainargs: Any = RealisticDefs.mainargsDocker.runEither(realisticArgs)
  @Benchmark def realistic_caseapp: Any  = RealisticDefs.caseappDocker(realisticArgs)

  // JVM-only rows: builder/DSL/reflection libraries, runtime comparison only
  @Benchmark def realistic_scopt: Any   = RealisticJvmDefs.scoptParse(realisticArgs)
  @Benchmark def realistic_scallop: Any = RealisticJvmDefs.scallopParse(realisticArgs)
  @Benchmark def realistic_picocli: Any = RealisticJvmDefs.picocliParse(realisticArr)

  // ---- flagged x mainargs ---------------------------------------------------------

  @Benchmark def bundled_flagged: Any  = FlaggedDefs.simple.parse(bundledArgs)
  @Benchmark def bundled_mainargs: Any = MainargsDefs.simple.constructRaw(bundledArgs)

  @Benchmark def leftover_flagged: Any  = FlaggedDefs.nums.parse(leftoverArgs)
  @Benchmark def leftover_mainargs: Any = MainargsDefs.nums.constructRaw(leftoverArgs)

  @Benchmark def map_flagged: Any  = FlaggedDefs.defines.parse(mapArgs)
  @Benchmark def map_mainargs: Any = MainargsDefs.defines.constructRaw(mapArgs)

  // ---- flagged x case-app ---------------------------------------------------------

  @Benchmark def counter_flagged: Any = FlaggedDefs.verbosity.parse(counterArgs)
  @Benchmark def counter_caseapp: Any = CaseappDefs.verbosity.detailedParse(counterArgs)

  @Benchmark def group_flagged: Any = FlaggedDefs.withGroup.parse(groupArgs)
  @Benchmark def group_caseapp: Any = CaseappDefs.withGroup.detailedParse(groupArgs)
