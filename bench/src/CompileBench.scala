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
  * derivation), `commands` (a three-command interface, each command with options).
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

  @Param(Array("options10", "options25", "commands"))
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
    case ("flagged", "options25")  => wide("import flagged.*", " derives Parser.Command", "")
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
    s"""import flagged.*
       |case class Config(
       |$fields10
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
      classPrefix: String = ""
  ): String =
    val fields = (1 to 25).map(i => s"    f$i: Int = $i").mkString(",\n")
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
      |  case Add(@positional name: String, url: String = "")
      |  case Remove(@positional name: String, force: Boolean = false)
      |  case Ls(verbose: Boolean = false, limit: Int = 10)
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

  private val baselineCommands =
    """enum Cli:
      |  case Add(name: String, url: String = "")
      |  case Remove(name: String, force: Boolean = false)
      |  case Ls(verbose: Boolean = false, limit: Int = 10)
      |object Use { val value = Cli.Ls() }
      |""".stripMargin
