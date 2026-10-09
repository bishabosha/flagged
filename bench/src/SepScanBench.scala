package bench

import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

/** Probe: the per-token cost of making the long-option value separator (`--foo=v`) a mode switch
  * instead of the constant `'='` — the split step of `Engine.route` in isolation, over the
  * realistic scenario's long-option tokens in both spellings.
  *
  *   - `constEq` — today's code shape: `token.indexOf('=')` with a constant char
  *   - `varChar` — the separator loaded from a field (a runtime-configurable separator)
  *   - `branchMode` — a boolean mode selecting between two constant-char scans
  *   - `eitherChar` — accept both `=` and `:`: two scans, split at the first of either
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
class SepScanBench:

  /** `separate`: bare option tokens (the value is the next token) — the scan finds nothing and
    * walks the whole token. `inline`: `--opt=value` spellings — the scan stops at the separator and
    * the split allocates two substrings.
    */
  @Param(Array("separate", "inline"))
  var spelling: String = ""

  private var tokens: Array[String] = null

  /** The configurable-separator variant's state: a plain field, so the JIT sees a load, not a
    * constant.
    */
  var sep: Char          = '='
  var colonMode: Boolean = false

  @Setup
  def setup(): Unit =
    tokens = spelling match
      case "separate" =>
        Array("--name", "--workdir", "--user", "--memory", "--cpus", "--restart", "--network",
          "--label", "--detach", "--rm", "--read-only", "--env")
      case "inline" =>
        Array("--name=web", "--workdir=/app", "--user=1000:1000", "--memory=512m", "--cpus=1.5",
          "--restart=on-failure", "--network=bridge", "--label=app=web", "--detach", "--rm",
          "--read-only", "--env=PGHOST=db")

  private inline def split(t: String, eq: Int, bh: Blackhole): Unit =
    val key         = if eq == -1 then t else t.substring(0, eq)
    val inlineValue = if eq == -1 then null else t.substring(eq + 1)
    bh.consume(key)
    bh.consume(inlineValue)

  @Benchmark
  def constEq(bh: Blackhole): Unit =
    val ts = tokens
    var i  = 0
    while i < ts.length do
      val t = ts(i)
      split(t, t.indexOf('='), bh)
      i += 1

  @Benchmark
  def varChar(bh: Blackhole): Unit =
    val s  = sep
    val ts = tokens
    var i  = 0
    while i < ts.length do
      val t = ts(i)
      split(t, t.indexOf(s), bh)
      i += 1

  @Benchmark
  def branchMode(bh: Blackhole): Unit =
    val colon = colonMode
    val ts    = tokens
    var i     = 0
    while i < ts.length do
      val t  = ts(i)
      val eq = if colon then t.indexOf(':') else t.indexOf('=')
      split(t, eq, bh)
      i += 1

  @Benchmark
  def eitherChar(bh: Blackhole): Unit =
    val ts = tokens
    var i  = 0
    while i < ts.length do
      val t  = ts(i)
      val a  = t.indexOf('=')
      val b  = t.indexOf(':')
      val eq = if a == -1 then b else if b == -1 then a else math.min(a, b)
      split(t, eq, bh)
      i += 1
