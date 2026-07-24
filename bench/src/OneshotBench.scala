package bench

import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.*
import bench.defs.*
import flagged.Parser
import mainargs.{ParserForClass, ParserForMethods}
import caseapp.Parser as CParser
import picocli.CommandLine

/** The whole per-process cost in one measurement: construct the parser *and* parse one command
  * line, per invocation — what a CLI run actually pays after class loading. [[ConstructBench]] and
  * [[RuntimeBench]] measure the two halves separately; this is their sum measured directly.
  *
  * Each library constructs what its idiom needs for the invocation: flagged and mainargs build the
  * whole command group (their derivation is one expression over the sum), case-app's first-token
  * dispatch constructs only the invoked command's parser, scopt rebuilds its `OParser` chain,
  * scallop's `ScallopConf` is construct-and-parse by design, and picocli rebuilds its reflective
  * model (`new CommandLine`).
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
class OneshotBench:

  private val simpleArgs = Seq("--foo", "hello", "--bar", "42", "--baz")

  private val realisticArgs = Seq(
    "run", "--name", "web", "-e", "PGHOST=db", "-e", "PGPORT=5432", "-p", "8080:80", "-p",
    "8443:443", "-v", "/srv/site:/usr/share/nginx/html:ro", "--label", "app=web", "--label",
    "env=prod", "--workdir", "/app", "--user", "1000:1000", "--memory", "512m", "--cpus", "1.5",
    "--restart", "on-failure", "--network", "bridge", "--detach", "--rm", "--read-only",
    "nginx:1.27", "nginx-debug"
  )
  private val realisticArr = realisticArgs.toArray

  @Setup
  def validate(): Unit =
    require(simple_flagged.isInstanceOf[steps.result.Result.Ok[?]], "flagged simple failed")
    require(RealisticDefs.agrees(realisticArgs), "realistic parsers disagree")
    require(RealisticJvmDefs.scoptOneshot(realisticArgs).nonEmpty, "scopt oneshot failed")

  @Benchmark def simple_flagged: Any  = Parser.Command.derived[FSimple].parse(simpleArgs)
  @Benchmark def simple_mainargs: Any = ParserForClass[MSimple].constructRaw(simpleArgs)
  @Benchmark def simple_caseapp: Any  = CParser[CSimple].detailedParse(simpleArgs)

  @Benchmark def realistic_flagged: Any =
    Parser.CommandGroup.derived[FDocker].parse(realisticArgs)
  @Benchmark def realistic_mainargs: Any =
    ParserForMethods(MDocker).runEither(realisticArgs)
  @Benchmark def realistic_caseapp: Any =
    // first-token dispatch constructs only the invoked command's parser, as a one-shot run would
    realisticArgs.head match
      case "run"  => CParser[CRun].detailedParse(realisticArgs.tail)
      case "pull" => CParser[CPull].detailedParse(realisticArgs.tail)
      case "ps"   => CParser[CPs].detailedParse(realisticArgs.tail)
      case other  => sys.error(s"unknown command: $other")

  @Benchmark def realistic_scopt: Any   = RealisticJvmDefs.scoptOneshot(realisticArgs)
  @Benchmark def realistic_scallop: Any = RealisticJvmDefs.scallopParse(realisticArgs)
  @Benchmark def realistic_picocli: Any =
    new CommandLine(new PicocliDocker).parseArgs(realisticArr*)
