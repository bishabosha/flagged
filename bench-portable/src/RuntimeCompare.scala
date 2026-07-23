package bench

import bench.defs.{FlaggedDefs, MainargsDefs, CaseappDefs, RealisticDefs}

/** Portable parse-latency comparison for platforms JMH cannot cover (the JVM numbers in
  * `bench/results.md` come from JMH; this harness exists for Scala.js, Scala.js-on-Wasm, and Scala
  * Native). Same scenarios and parser instances as `bench.RuntimeBench`; each benchmark is
  * auto-calibrated to ~250 ms rounds, 3 warmup + 5 measured, best round reported.
  *
  * ./mill bench-portable.jvm.run # JVM (sanity reference) ./mill bench-portable.js.run # Node
  * ./mill bench-portable.jsWasm.run # Node, WebAssembly backend ./mill bench-portable.native.run #
  * Native, release-fast
  */
object RuntimeCompare:

  private val emptyArgs    = Seq.empty[String]
  private val simpleArgs   = Seq("--foo", "hello", "--bar", "42", "--baz")
  private val repeatedArgs = Seq("--qux", "a", "--qux", "b", "--qux", "c", "--qux", "d")
  private val bundledArgs  = Seq("-bfhello", "--bar", "7")
  private val counterArgs  = Seq("-v", "-v", "-v", "--target", "x")
  private val groupArgs    = Seq("--host", "h", "--port", "8080", "-q", "--log-level", "warn")
  private val leftoverArgs = Seq("-s", "2", "1", "2", "3", "4", "5")
  // options before positionals: mainargs' Leftover consumes everything after the image token
  private val realisticArgs = Seq(
    "run", "--name", "web", "-e", "PGHOST=db", "-e", "PGPORT=5432", "-p", "8080:80", "-p",
    "8443:443", "-v", "/srv/site:/usr/share/nginx/html:ro", "--label", "app=web", "--label",
    "env=prod", "--workdir", "/app", "--user", "1000:1000", "--memory", "512m", "--cpus", "1.5",
    "--restart", "on-failure", "--network", "bridge", "--detach", "--rm", "--read-only",
    "nginx:1.27", "nginx-debug"
  )

  private val benchmarks: List[(String, () => Any)] = List(
    "empty_flagged"      -> (() => FlaggedDefs.simple.parse(emptyArgs)),
    "empty_mainargs"     -> (() => MainargsDefs.simple.constructRaw(emptyArgs)),
    "empty_caseapp"      -> (() => CaseappDefs.simple.detailedParse(emptyArgs)),
    "simple_flagged"     -> (() => FlaggedDefs.simple.parse(simpleArgs)),
    "simple_mainargs"    -> (() => MainargsDefs.simple.constructRaw(simpleArgs)),
    "simple_caseapp"     -> (() => CaseappDefs.simple.detailedParse(simpleArgs)),
    "repeated_flagged"   -> (() => FlaggedDefs.simple.parse(repeatedArgs)),
    "repeated_mainargs"  -> (() => MainargsDefs.simple.constructRaw(repeatedArgs)),
    "repeated_caseapp"   -> (() => CaseappDefs.simple.detailedParse(repeatedArgs)),
    "bundled_flagged"    -> (() => FlaggedDefs.simple.parse(bundledArgs)),
    "bundled_mainargs"   -> (() => MainargsDefs.simple.constructRaw(bundledArgs)),
    "counter_flagged"    -> (() => FlaggedDefs.verbosity.parse(counterArgs)),
    "counter_caseapp"    -> (() => CaseappDefs.verbosity.detailedParse(counterArgs)),
    "group_flagged"      -> (() => FlaggedDefs.withGroup.parse(groupArgs)),
    "group_caseapp"      -> (() => CaseappDefs.withGroup.detailedParse(groupArgs)),
    "leftover_flagged"   -> (() => FlaggedDefs.nums.parse(leftoverArgs)),
    "leftover_mainargs"  -> (() => MainargsDefs.nums.constructRaw(leftoverArgs)),
    "realistic_flagged"  -> (() => RealisticDefs.flaggedDocker.parse(realisticArgs)),
    "realistic_mainargs" -> (() => RealisticDefs.mainargsDocker.runEither(realisticArgs)),
    "realistic_caseapp"  -> (() => RealisticDefs.caseappDocker(realisticArgs))
  )

  /** A failed parse can be faster than a successful one; assert every scenario succeeds. */
  private def validate(): Unit =
    val failing = benchmarks.collect {
      case (name, f) if !succeeded(f()) => name
    }
    if failing.nonEmpty then sys.error(s"scenarios do not parse: ${failing.mkString(", ")}")

  private def succeeded(r: Any): Boolean = r match
    case flagged.Ok(_)              => true
    case mainargs.Result.Success(_) => true
    case Right(_)                   => true
    case _                          => false

  private var sink = 0 // defeats dead-code elimination across backends

  private def round(f: () => Any, iters: Int): Double =
    var i  = 0
    val t0 = System.nanoTime()
    while i < iters do
      if f() == null then sink += 1
      i += 1
    (System.nanoTime() - t0).toDouble / iters

  private def measure(f: () => Any): Double =
    val estimate = round(f, 1000) // also warms
    val iters    = math.max(1000, math.min(2_000_000, (250e6 / estimate).toInt))
    (1 to 3).foreach(_ => round(f, iters)) // warmup rounds
    (1 to 5).map(_ => round(f, iters)).min

  def main(args: Array[String]): Unit =
    validate()
    println(f"${"benchmark"}%-20s ${"ns/op (best of 5)"}%s")
    benchmarks.foreach { (name, f) =>
      println(f"$name%-20s ${measure(f)}%10.1f")
    }
    if sink == -1 then println(sink) // keep the sink observable
