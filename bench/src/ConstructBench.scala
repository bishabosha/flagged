package bench

import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.*
import bench.defs.*
import flagged.Parser
import mainargs.{ParserForClass, ParserForMethods}
import caseapp.Parser as CParser

/** Runtime construction cost of the parser value itself — the work `RuntimeBench` amortizes into
  * setup. A CLI process builds its parser once and parses once, so the honest per-run cost is
  * construction + parse; this benchmark measures the construction half for the three derivation
  * libraries on the same grammars.
  *
  * Each method re-executes what a fresh process pays after class loading: flagged's inline
  * derivation body (the field walk feeding `Assemble.FieldsBuilder`: validated specs), mainargs'
  * `ParserForClass`/`ParserForMethods` materialization, case-app's `Parser[T]` derivation.
  * flagged's `derives` clause caches the given per companion, so the benchmark invokes `derived`
  * directly; the other two are macro/inline materializations that rebuild at every call site
  * invocation alike. Shared value-level givens (element readers such as `Parser.Value[Int]`,
  * `TokensReader`s, `ArgParser`s) are singletons for every library and are not rebuilt — for all
  * three, what is measured is the per-parser assembly on top of them.
  *
  * `realistic_caseapp` constructs the three per-command parsers its dispatch needs — the same three
  * the runtime scenario's setup builds.
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
class ConstructBench:

  @Benchmark def simple_flagged: Any  = Parser.Command.derived[FSimple]
  @Benchmark def simple_mainargs: Any = ParserForClass[MSimple]
  @Benchmark def simple_caseapp: Any  = CParser[CSimple]

  @Benchmark def realistic_flagged: Any  = Parser.CommandGroup.derived[FDocker]
  @Benchmark def realistic_mainargs: Any = ParserForMethods(MDocker)
  @Benchmark def realistic_caseapp: Any  = (CParser[CRun], CParser[CPull], CParser[CPs])
