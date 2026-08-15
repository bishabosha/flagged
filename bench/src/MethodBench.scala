package bench

import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.*
import bench.defs.MethodDefs

/** Parse-and-invoke latency for method-based commands: flagged's `@cmd` derivation (`Parser.method`
  * / `Parser.methods`) against mainargs' `ParserForMethods`. Both sides select a method, parse its
  * parameters, and invoke it, returning the method's result. Parsers are built once in setup; add
  * `-prof gc` to also measure allocation per call (`gc.alloc.rate.norm`).
  *
  * Scenarios:
  *   - `method` — a lone command method, same grammar as `RuntimeBench`'s `simple`
  *   - `commands` — a three-command interface dispatching on the first token
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
class MethodBench:

  private val methodArgs   = Seq("--foo", "hello", "--bar", "42", "--baz")
  private val commandsArgs = Seq("add", "core", "--url", "https://x.git")

  /** Both libraries must succeed and agree on the invoked result. */
  @Setup
  def validate(): Unit =
    def fVal(r: flagged.ParseResult[Int]): Int = r match
      case flagged.Result.Ok(v) => v
      case other                => throw new IllegalStateException(s"flagged failed: $other")
    def mVal(r: Either[String, Any]): Any = r match
      case Right(v) => v
      case Left(e)  => throw new IllegalStateException(s"mainargs failed: $e")
    val checks = Seq(
      "method" -> (
        fVal(MethodDefs.flaggedApp.parse(methodArgs)),
        mVal(MethodDefs.mainargsApp.runEither(methodArgs))
      ),
      "commands" -> (
        fVal(MethodDefs.flaggedCli.parse(commandsArgs)),
        mVal(MethodDefs.mainargsCli.runEither(commandsArgs, allowPositional = true))
      )
    )
    for (name, (f, m)) <- checks do
      if f != m then throw new IllegalStateException(s"$name: flagged=$f mainargs=$m")

  @Benchmark def method_flagged: Any  = MethodDefs.flaggedApp.parse(methodArgs)
  @Benchmark def method_mainargs: Any = MethodDefs.mainargsApp.runEither(methodArgs)

  @Benchmark def commands_flagged: Any  = MethodDefs.flaggedCli.parse(commandsArgs)
  @Benchmark def commands_mainargs: Any =
    MethodDefs.mainargsCli.runEither(commandsArgs, allowPositional = true)
