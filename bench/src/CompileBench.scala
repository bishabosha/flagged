package bench

import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.*
import dotty.tools.dotc.Driver
import dotty.tools.dotc.reporting.{Reporter, StoreReporter}
import scala.compiletime.uninitialized

/** Time to compile one source file whose cost is dominated by parser derivation, via the dotty
  * `Driver` with this benchmark's own classpath. `baseline` compiles the same declarations with no
  * library involved, giving the compiler's floor for the file shape.
  *
  * Scenarios: `options10` (a mixed 10-field options class), `options25` (25 fields — wide
  * derivation), `commands` (a three-command interface, each command with options), `methods` (the
  * same interface as `@cmd`/`@main` command methods; case-app has no method-based API, so its entry
  * reuses the command-objects encoding, and mainargs' `commands` entry is already
  * `ParserForMethods` — its two entries measure the same source), `realistic` (the docker-style
  * interface of `bench.defs.RealisticDefs` — one subcommand level, one large command).
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 5)
@Fork(1)
class CompileBench:

  @Param(Array("flagged", "mainargs", "caseapp", "baseline"))
  var lib: String = uninitialized

  @Param(Array("options10", "options25", "commands", "methods", "realistic"))
  var scenario: String = uninitialized

  private var compileArgs: Array[String] = uninitialized

  @Setup
  def setup(): Unit =
    val dir     = Files.createTempDirectory("flagged-compile-bench")
    val srcFile = dir.resolve(s"$lib-$scenario.scala")
    Files.writeString(srcFile, BenchSources(lib, scenario))
    val outDir = Files.createDirectories(dir.resolve("out"))
    compileArgs = Array(
      "-d",
      outDir.toString,
      "-classpath",
      sys.props("java.class.path"),
      "-nowarn",
      srcFile.toString
    )
    // fail fast, with diagnostics on the default (console) reporter
    if new Driver().process(compileArgs).hasErrors then
      throw new IllegalStateException(s"benchmark source does not compile: $lib/$scenario")

  @Benchmark
  def compile(): Reporter =
    val reporter = new Driver().process(compileArgs, new StoreReporter())
    if reporter.hasErrors then
      throw new IllegalStateException(s"compilation failed: $lib/$scenario")
    reporter

/** The virtual source files: per library, the same CLI surface written idiomatically for it. */
object BenchSources:

  def apply(lib: String, scenario: String): String = (lib, scenario) match
    case ("flagged", "options10")  => flaggedOptions10
    case ("mainargs", "options10") => mainargsOptions10
    case ("caseapp", "options10")  => caseappOptions10
    case ("baseline", "options10") => baselineOptions10
    case ("flagged", "options25")  =>
      wide("import flagged.*", " derives Parser.Command", "", fieldPrefix = "@opt ")
    case ("mainargs", "options25") =>
      wide(
        "import mainargs.{main, arg, ParserForClass}",
        "",
        "object Use { val parser = ParserForClass[Wide] }",
        classPrefix = "@main "
      )
    case ("caseapp", "options25") =>
      wide(
        "import caseapp.*",
        "",
        "object Use { val parser = Parser[Wide]; val help = Help[Wide] }"
      )
    case ("baseline", "options25") => wide("", "", "object Use { val value = Wide() }")
    case ("flagged", "commands")   => flaggedCommands
    case ("mainargs", "commands")  => mainargsCommands
    case ("caseapp", "commands")   => caseappCommands
    case ("baseline", "commands")  => baselineCommands
    case ("flagged", "methods")    => flaggedMethods
    case ("mainargs", "methods")   => mainargsCommands // ParserForMethods is its methods form
    case ("caseapp", "methods")    => caseappCommands  // no method-based API: closest encoding
    case ("baseline", "methods")   => baselineMethods
    case ("flagged", "realistic")  => flaggedRealistic
    case ("mainargs", "realistic") => mainargsRealistic
    case ("caseapp", "realistic")  => caseappRealistic
    case ("baseline", "realistic") => baselineRealistic
    case other                     => throw new IllegalArgumentException(other.toString)

  private val fields10 =
    """    foo: String = "x",
      |    bar: Int = 0,
      |    baz: Boolean = false,
      |    qux: List[String] = Nil,
      |    host: String = "localhost",
      |    port: Int = 80,
      |    verbose: Boolean = false,
      |    retries: Int = 3,
      |    timeout: Double = 1.5,
      |    tag: Option[String] = None""".stripMargin

  private val flaggedOptions10 =
    """import flagged.*
      |case class Config(
      |    @opt foo: String = "x",
      |    @opt bar: Int = 0,
      |    @opt baz: Boolean = false,
      |    @opt qux: List[String] = Nil,
      |    @opt host: String = "localhost",
      |    @opt port: Int = 80,
      |    @opt verbose: Boolean = false,
      |    @opt retries: Int = 3,
      |    @opt timeout: Double = 1.5,
      |    @opt tag: Option[String] = None
      |) derives Parser.Command
      |object Use { val parser = summon[Parser.Command[Config]] }
      |""".stripMargin

  private val mainargsOptions10 =
    s"""import mainargs.{main, arg, Flag, ParserForClass}
       |@main case class Config(
       |    foo: String = "x",
       |    bar: Int = 0,
       |    baz: Flag = Flag(),
       |    qux: Seq[String] = Nil,
       |    host: String = "localhost",
       |    port: Int = 80,
       |    verbose: Flag = Flag(),
       |    retries: Int = 3,
       |    timeout: Double = 1.5,
       |    tag: Option[String] = None
       |)
       |object Use { val parser = ParserForClass[Config] }
       |""".stripMargin

  private val caseappOptions10 =
    s"""import caseapp.*
       |final case class Config(
       |$fields10
       |)
       |object Use { val parser = Parser[Config]; val help = Help[Config] }
       |""".stripMargin

  private val baselineOptions10 =
    s"""case class Config(
       |$fields10
       |)
       |object Use { val value = Config() }
       |""".stripMargin

  private def wide(
      imports: String,
      derivesClause: String,
      use: String,
      classPrefix: String = "",
      fieldPrefix: String = ""
  ): String =
    val fields = (1 to 25).map(i => s"    ${fieldPrefix}f$i: Int = $i").mkString(",\n")
    s"""$imports
       |${classPrefix}case class Wide(
       |$fields
       |)$derivesClause
       |${
        if derivesClause.nonEmpty then "object Use { val parser = summon[Parser.Command[Wide]] }"
        else use
      }
       |""".stripMargin

  private val flaggedCommands =
    """import flagged.*
      |enum Cli derives Parser.CommandGroup:
      |  case Add(name: String, @opt url: String = "")
      |  case Remove(name: String, @opt force: Boolean = false)
      |  case Ls(@opt verbose: Boolean = false, @opt limit: Int = 10)
      |object Use { val parser = summon[Parser.CommandGroup[Cli]] }
      |""".stripMargin

  private val mainargsCommands =
    """import mainargs.{main, arg, Flag, ParserForMethods}
      |object Cli {
      |  @main def add(name: String, url: String = "") = ()
      |  @main def remove(name: String, force: Flag = Flag()) = ()
      |  @main def ls(verbose: Flag = Flag(), limit: Int = 10) = ()
      |  val parser = ParserForMethods(this)
      |}
      |""".stripMargin

  private val caseappCommands =
    """import caseapp.*
      |final case class AddOptions(url: String = "")
      |object Add extends Command[AddOptions] {
      |  def run(options: AddOptions, args: RemainingArgs): Unit = ()
      |}
      |final case class RemoveOptions(force: Boolean = false)
      |object Remove extends Command[RemoveOptions] {
      |  def run(options: RemoveOptions, args: RemainingArgs): Unit = ()
      |}
      |final case class LsOptions(verbose: Boolean = false, limit: Int = 10)
      |object Ls extends Command[LsOptions] {
      |  def run(options: LsOptions, args: RemainingArgs): Unit = ()
      |}
      |object Cli extends CommandsEntryPoint {
      |  def progName = "cli"
      |  def commands = Seq(Add, Remove, Ls)
      |}
      |""".stripMargin

  private val flaggedMethods =
    """import flagged.*
      |object Cli:
      |  @cmd def add(name: String, @opt url: String = ""): Unit = ()
      |  @cmd def remove(name: String, @opt force: Boolean = false): Unit = ()
      |  @cmd def ls(@opt verbose: Boolean = false, @opt limit: Int = 10): Unit = ()
      |object Use { val parser = Parser.methods(Cli) }
      |""".stripMargin

  private val baselineMethods =
    """object Cli:
      |  def add(name: String, url: String = ""): Unit = ()
      |  def remove(name: String, force: Boolean = false): Unit = ()
      |  def ls(verbose: Boolean = false, limit: Int = 10): Unit = ()
      |object Use { val value = Cli.ls() }
      |""".stripMargin

  private val baselineCommands =
    """enum Cli:
      |  case Add(name: String, url: String = "")
      |  case Remove(name: String, force: Boolean = false)
      |  case Ls(verbose: Boolean = false, limit: Int = 10)
      |object Use { val value = Cli.Ls() }
      |""".stripMargin

  // The docker-style realistic interface (`bench.defs.RealisticDefs`), per library. Field lists
  // mirror the runtime defs; the source shape is what a user would write for that library.

  private val flaggedRealistic =
    """import flagged.*
      |enum Docker derives Parser.CommandGroup:
      |  case Run(
      |      @opt name: Option[String] = None,
      |      @opt(short = 'e') env: List[String] = Nil,
      |      @opt(short = 'p') publish: List[String] = Nil,
      |      @opt(short = 'v') volume: List[String] = Nil,
      |      @opt(short = 'l') label: List[String] = Nil,
      |      @opt(short = 'w') workdir: Option[String] = None,
      |      @opt(short = 'u') user: Option[String] = None,
      |      @opt entrypoint: Option[String] = None,
      |      @opt network: String = "default",
      |      @opt restart: String = "no",
      |      @opt(short = 'm') memory: Option[String] = None,
      |      @opt cpus: Option[Double] = None,
      |      @opt pull: String = "missing",
      |      @opt(short = 'd') detach: Boolean = false,
      |      @opt rm: Boolean = false,
      |      @opt(short = 'i') interactive: Boolean = false,
      |      @opt(short = 't') tty: Boolean = false,
      |      @opt readOnly: Boolean = false,
      |      image: String,
      |      cmd: List[String] = Nil
      |  )
      |  case Pull(
      |      @opt platform: Option[String] = None,
      |      @opt(short = 'q') quiet: Boolean = false,
      |      @opt(short = 'a') allTags: Boolean = false,
      |      image: String
      |  )
      |  case Ps(
      |      @opt(short = 'a') all: Boolean = false,
      |      @opt(short = 'q') quiet: Boolean = false,
      |      @opt(short = 'f') filter: List[String] = Nil,
      |      @opt(short = 'n') last: Int = -1,
      |      @opt format: Option[String] = None
      |  )
      |object Use { val parser = summon[Parser.CommandGroup[Docker]] }
      |""".stripMargin

  private val mainargsRealistic =
    """import mainargs.{main, arg, Flag, Leftover, ParserForMethods}
      |object Docker {
      |  @main def run(
      |      name: Option[String] = None,
      |      @arg(short = 'e') env: Seq[String] = Nil,
      |      @arg(short = 'p') publish: Seq[String] = Nil,
      |      @arg(short = 'v') volume: Seq[String] = Nil,
      |      @arg(short = 'l') label: Seq[String] = Nil,
      |      @arg(short = 'w') workdir: Option[String] = None,
      |      @arg(short = 'u') user: Option[String] = None,
      |      entrypoint: Option[String] = None,
      |      network: String = "default",
      |      restart: String = "no",
      |      @arg(short = 'm') memory: Option[String] = None,
      |      cpus: Option[Double] = None,
      |      pull: String = "missing",
      |      @arg(short = 'd') detach: Flag = Flag(),
      |      rm: Flag = Flag(),
      |      @arg(short = 'i') interactive: Flag = Flag(),
      |      @arg(short = 't') tty: Flag = Flag(),
      |      readOnly: Flag = Flag(),
      |      args: Leftover[String]
      |  ) = ()
      |  @main def pull(
      |      platform: Option[String] = None,
      |      @arg(short = 'q') quiet: Flag = Flag(),
      |      @arg(short = 'a') allTags: Flag = Flag(),
      |      image: String
      |  ) = ()
      |  @main def ps(
      |      @arg(short = 'a') all: Flag = Flag(),
      |      @arg(short = 'q') quiet: Flag = Flag(),
      |      @arg(short = 'f') filter: Seq[String] = Nil,
      |      @arg(short = 'n') last: Int = -1,
      |      format: Option[String] = None
      |  ) = ()
      |  val parser = ParserForMethods(this)
      |}
      |""".stripMargin

  private val caseappRealistic =
    """import caseapp.*
      |final case class RunOptions(
      |    name: Option[String] = None,
      |    @ExtraName("e") env: List[String] = Nil,
      |    @ExtraName("p") publish: List[String] = Nil,
      |    @ExtraName("v") volume: List[String] = Nil,
      |    @ExtraName("l") label: List[String] = Nil,
      |    @ExtraName("w") workdir: Option[String] = None,
      |    @ExtraName("u") user: Option[String] = None,
      |    entrypoint: Option[String] = None,
      |    network: String = "default",
      |    restart: String = "no",
      |    @ExtraName("m") memory: Option[String] = None,
      |    cpus: Option[Double] = None,
      |    pull: String = "missing",
      |    @ExtraName("d") detach: Boolean = false,
      |    rm: Boolean = false,
      |    @ExtraName("i") interactive: Boolean = false,
      |    @ExtraName("t") tty: Boolean = false,
      |    readOnly: Boolean = false
      |)
      |object Run extends Command[RunOptions] {
      |  def run(options: RunOptions, args: RemainingArgs): Unit = ()
      |}
      |final case class PullOptions(
      |    platform: Option[String] = None,
      |    @ExtraName("q") quiet: Boolean = false,
      |    @ExtraName("a") allTags: Boolean = false
      |)
      |object Pull extends Command[PullOptions] {
      |  def run(options: PullOptions, args: RemainingArgs): Unit = ()
      |}
      |final case class PsOptions(
      |    @ExtraName("a") all: Boolean = false,
      |    @ExtraName("q") quiet: Boolean = false,
      |    @ExtraName("f") filter: List[String] = Nil,
      |    @ExtraName("n") last: Int = -1,
      |    format: Option[String] = None
      |)
      |object Ps extends Command[PsOptions] {
      |  def run(options: PsOptions, args: RemainingArgs): Unit = ()
      |}
      |object Docker extends CommandsEntryPoint {
      |  def progName = "docker"
      |  def commands = Seq(Run, Pull, Ps)
      |}
      |""".stripMargin

  private val baselineRealistic =
    """enum Docker:
      |  case Run(
      |      name: Option[String] = None,
      |      env: List[String] = Nil,
      |      publish: List[String] = Nil,
      |      volume: List[String] = Nil,
      |      label: List[String] = Nil,
      |      workdir: Option[String] = None,
      |      user: Option[String] = None,
      |      entrypoint: Option[String] = None,
      |      network: String = "default",
      |      restart: String = "no",
      |      memory: Option[String] = None,
      |      cpus: Option[Double] = None,
      |      pull: String = "missing",
      |      detach: Boolean = false,
      |      rm: Boolean = false,
      |      interactive: Boolean = false,
      |      tty: Boolean = false,
      |      readOnly: Boolean = false,
      |      image: String,
      |      cmd: List[String] = Nil
      |  )
      |  case Pull(
      |      platform: Option[String] = None,
      |      quiet: Boolean = false,
      |      allTags: Boolean = false,
      |      image: String
      |  )
      |  case Ps(
      |      all: Boolean = false,
      |      quiet: Boolean = false,
      |      filter: List[String] = Nil,
      |      last: Int = -1,
      |      format: Option[String] = None
      |  )
      |object Use { val value = Docker.Ps() }
      |""".stripMargin
