package bench.defs

import scala.jdk.CollectionConverters.*
import org.rogach.scallop.*
import picocli.CommandLine
import scopt.OParser

// ---- the realistic docker-style CLI in scopt, scallop, and picocli -------------
// JVM-only runtime rows for the `realistic` scenario (RealisticDefs.scala holds the
// flagged/mainargs/case-app encodings and the expected values). These libraries
// build their parsers with builders, a DSL, or reflection — there is no derivation
// to measure at compile time, so they appear only in the runtime table.

/** scopt has no per-command config values: one flat config accumulates every command's options
  * (fields of the unexercised `pull`/`ps` are prefixed to avoid name clashes), with a `command`
  * discriminator set by `cmd`. Collections accumulate by copy-and-append, as in scopt's readme.
  */
final case class ScoptConfig(
    command: String = "",
    name: Option[String] = None,
    env: List[String] = Nil,
    publish: List[String] = Nil,
    volume: List[String] = Nil,
    label: List[String] = Nil,
    workdir: Option[String] = None,
    user: Option[String] = None,
    entrypoint: Option[String] = None,
    network: String = "default",
    restart: String = "no",
    memory: Option[String] = None,
    cpus: Option[Double] = None,
    pull: String = "missing",
    detach: Boolean = false,
    rm: Boolean = false,
    interactive: Boolean = false,
    tty: Boolean = false,
    readOnly: Boolean = false,
    image: String = "",
    cmd: List[String] = Nil,
    pullPlatform: Option[String] = None,
    pullQuiet: Boolean = false,
    pullAllTags: Boolean = false,
    pullImage: String = "",
    psAll: Boolean = false,
    psQuiet: Boolean = false,
    psFilter: List[String] = Nil,
    psLast: Int = -1,
    psFormat: Option[String] = None
)

/** scallop couples definition and parse: a `ScallopConf` is built and verified per argument list,
  * so parser construction is part of every parse — that is the library's usage model.
  */
final class ScallopDocker(args: Seq[String]) extends ScallopConf(args):
  object run extends Subcommand("run"):
    val name        = opt[String](noshort = true)
    val env         = opt[List[String]](short = 'e')
    val publish     = opt[List[String]](short = 'p')
    val volume      = opt[List[String]](short = 'v')
    val label       = opt[List[String]](short = 'l')
    val workdir     = opt[String](short = 'w')
    val user        = opt[String](short = 'u')
    val entrypoint  = opt[String](noshort = true)
    val network     = opt[String](default = Some("default"), noshort = true)
    val restart     = opt[String](default = Some("no"), noshort = true)
    val memory      = opt[String](short = 'm')
    val cpus        = opt[Double](noshort = true)
    val pull        = opt[String](default = Some("missing"), noshort = true)
    val detach      = opt[Boolean](short = 'd')
    val rm          = opt[Boolean](noshort = true)
    val interactive = opt[Boolean](short = 'i')
    val tty         = opt[Boolean](short = 't')
    val readOnly    = opt[Boolean](name = "read-only", noshort = true)
    val image       = trailArg[String]()
    val cmd         = trailArg[List[String]](required = false, default = Some(Nil))
  addSubcommand(run)
  object pull extends Subcommand("pull"):
    val platform = opt[String](noshort = true)
    val quiet    = opt[Boolean](short = 'q')
    val allTags  = opt[Boolean](name = "all-tags", short = 'a')
    val image    = trailArg[String]()
  addSubcommand(pull)
  object ps extends Subcommand("ps"):
    val all    = opt[Boolean](short = 'a')
    val quiet  = opt[Boolean](short = 'q')
    val filter = opt[List[String]](short = 'f')
    val last   = opt[Int](short = 'n', default = Some(-1))
    val format = opt[String](noshort = true)
  addSubcommand(ps)
  override def onError(e: Throwable): Unit = throw e
  verify()

object RealisticJvmDefs:

  private val builder     = OParser.builder[ScoptConfig]
  private val scoptParser =
    import builder.*
    OParser.sequence(
      programName("docker"),
      cmd("run")
        .action((_, c) => c.copy(command = "run"))
        .children(
          opt[String]("name").action((v, c) => c.copy(name = Some(v))),
          opt[String]('e', "env").unbounded().action((v, c) => c.copy(env = c.env :+ v)),
          opt[String]('p', "publish")
            .unbounded()
            .action((v, c) => c.copy(publish = c.publish :+ v)),
          opt[String]('v', "volume").unbounded().action((v, c) => c.copy(volume = c.volume :+ v)),
          opt[String]('l', "label").unbounded().action((v, c) => c.copy(label = c.label :+ v)),
          opt[String]('w', "workdir").action((v, c) => c.copy(workdir = Some(v))),
          opt[String]('u', "user").action((v, c) => c.copy(user = Some(v))),
          opt[String]("entrypoint").action((v, c) => c.copy(entrypoint = Some(v))),
          opt[String]("network").action((v, c) => c.copy(network = v)),
          opt[String]("restart").action((v, c) => c.copy(restart = v)),
          opt[String]('m', "memory").action((v, c) => c.copy(memory = Some(v))),
          opt[Double]("cpus").action((v, c) => c.copy(cpus = Some(v))),
          opt[String]("pull").action((v, c) => c.copy(pull = v)),
          opt[Unit]('d', "detach").action((_, c) => c.copy(detach = true)),
          opt[Unit]("rm").action((_, c) => c.copy(rm = true)),
          opt[Unit]('i', "interactive").action((_, c) => c.copy(interactive = true)),
          opt[Unit]('t', "tty").action((_, c) => c.copy(tty = true)),
          opt[Unit]("read-only").action((_, c) => c.copy(readOnly = true)),
          arg[String]("image").action((v, c) => c.copy(image = v)),
          arg[String]("cmd").unbounded().optional().action((v, c) => c.copy(cmd = c.cmd :+ v))
        ),
      cmd("pull")
        .action((_, c) => c.copy(command = "pull"))
        .children(
          opt[String]("platform").action((v, c) => c.copy(pullPlatform = Some(v))),
          opt[Unit]('q', "quiet").action((_, c) => c.copy(pullQuiet = true)),
          opt[Unit]('a', "all-tags").action((_, c) => c.copy(pullAllTags = true)),
          arg[String]("image").action((v, c) => c.copy(pullImage = v))
        ),
      cmd("ps")
        .action((_, c) => c.copy(command = "ps"))
        .children(
          opt[Unit]('a', "all").action((_, c) => c.copy(psAll = true)),
          opt[Unit]('q', "quiet").action((_, c) => c.copy(psQuiet = true)),
          opt[String]('f', "filter")
            .unbounded()
            .action((v, c) => c.copy(psFilter = c.psFilter :+ v)),
          opt[Int]('n', "last").action((v, c) => c.copy(psLast = v)),
          opt[String]("format").action((v, c) => c.copy(psFormat = Some(v)))
        )
    )

  def scoptParse(args: Seq[String]): Option[ScoptConfig] =
    OParser.parse(scoptParser, args, ScoptConfig())

  def scallopParse(args: Seq[String]): ScallopDocker = new ScallopDocker(args)

  /** Built once, like the other libraries' parsers: model construction (reflection over the
    * annotated classes) happens here, and `parseArgs` resets annotated fields to their initial
    * values before each parse.
    */
  val picocliDocker: CommandLine = new CommandLine(new PicocliDocker)

  def picocliParse(args: Array[String]): CommandLine.ParseResult = picocliDocker.parseArgs(args*)

  // ---- setup validation: each library parses the realistic line to the expected values ------

  def scoptAgrees(args: Seq[String]): Boolean =
    val e = RealisticDefs.expectedF
    scoptParse(args) == Some(
      ScoptConfig(
        command = "run",
        name = e.name,
        env = e.env,
        publish = e.publish,
        volume = e.volume,
        label = e.label,
        workdir = e.workdir,
        user = e.user,
        entrypoint = e.entrypoint,
        network = e.network,
        restart = e.restart,
        memory = e.memory,
        cpus = e.cpus,
        pull = e.pull,
        detach = e.detach,
        rm = e.rm,
        interactive = e.interactive,
        tty = e.tty,
        readOnly = e.readOnly,
        image = e.image,
        cmd = e.cmd
      )
    )

  def scallopAgrees(args: Seq[String]): Boolean =
    val e = RealisticDefs.expectedF
    val c = scallopParse(args)
    val r = c.run
    c.subcommand == Some(r) &&
    r.name.toOption == e.name &&
    r.env.toOption.getOrElse(Nil) == e.env &&
    r.publish.toOption.getOrElse(Nil) == e.publish &&
    r.volume.toOption.getOrElse(Nil) == e.volume &&
    r.label.toOption.getOrElse(Nil) == e.label &&
    r.workdir.toOption == e.workdir &&
    r.user.toOption == e.user &&
    r.entrypoint.toOption == e.entrypoint &&
    r.network() == e.network &&
    r.restart() == e.restart &&
    r.memory.toOption == e.memory &&
    r.cpus.toOption == e.cpus &&
    r.pull() == e.pull &&
    r.detach() == e.detach &&
    r.rm() == e.rm &&
    r.interactive() == e.interactive &&
    r.tty() == e.tty &&
    r.readOnly() == e.readOnly &&
    r.image() == e.image &&
    r.cmd() == e.cmd

  def picocliAgrees(args: Array[String]): Boolean =
    val e               = RealisticDefs.expectedF
    def once(): Boolean =
      val pr = picocliParse(args)
      val r  = picocliDocker.getSubcommands.get("run").getCommand[PicocliDocker.Run]
      pr.subcommand() != null &&
      Option(r.name) == e.name &&
      r.env.asScala.toList == e.env &&
      r.publish.asScala.toList == e.publish &&
      r.volume.asScala.toList == e.volume &&
      r.label.asScala.toList == e.label &&
      Option(r.workdir) == e.workdir &&
      Option(r.user) == e.user &&
      Option(r.entrypoint) == e.entrypoint &&
      r.network == e.network &&
      r.restart == e.restart &&
      Option(r.memory) == e.memory &&
      Option(r.cpus).map(_.doubleValue) == e.cpus &&
      r.pull == e.pull &&
      r.detach == e.detach &&
      r.rm == e.rm &&
      r.interactive == e.interactive &&
      r.tty == e.tty &&
      r.readOnly == e.readOnly &&
      r.image == e.image &&
      r.cmd.asScala.toList == e.cmd
    // twice: a reused CommandLine must reset per-parse state (list options would otherwise grow)
    once() && once()
