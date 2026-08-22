package bench.defs

import flagged.{opt, Parser}
import mainargs.{main, arg, Flag, Leftover, ParserForMethods}
import caseapp.{ExtraName, Parser as CParser}

// ---- realistic three-way scenario: a docker-style CLI --------------------------
// One level of subcommands with one large command. The surface is modeled on a
// subset of Docker's `run`, `pull`, and `ps` (github.com/docker/cli, Apache-2.0 —
// the same license as this repository); only option names and shapes are
// replicated, no code. Each library uses its idiomatic subcommand encoding:
// flagged an enum `Parser.CommandGroup`, mainargs `ParserForMethods` (methods are
// its subcommand form, so a successful parse invokes; the bodies construct the
// same record the others produce), case-app options classes dispatched on the
// first token as `CommandsEntryPoint` does.

enum FDocker derives Parser.CommandGroup:
  case Run(
      @opt name: Option[String] = None,
      @opt(short = 'e') env: List[String] = Nil,
      @opt(short = 'p') publish: List[String] = Nil,
      @opt(short = 'v') volume: List[String] = Nil,
      @opt(short = 'l') label: List[String] = Nil,
      @opt(short = 'w') workdir: Option[String] = None,
      @opt(short = 'u') user: Option[String] = None,
      @opt entrypoint: Option[String] = None,
      @opt network: String = "default",
      @opt restart: String = "no",
      @opt(short = 'm') memory: Option[String] = None,
      @opt cpus: Option[Double] = None,
      @opt pull: String = "missing",
      @opt(short = 'd') detach: Boolean = false,
      @opt rm: Boolean = false,
      @opt(short = 'i') interactive: Boolean = false,
      @opt(short = 't') tty: Boolean = false,
      @opt readOnly: Boolean = false,
      image: String,
      cmd: List[String] = Nil
  )
  case Pull(
      @opt platform: Option[String] = None,
      @opt(short = 'q') quiet: Boolean = false,
      @opt(short = 'a') allTags: Boolean = false,
      image: String
  )
  case Ps(
      @opt(short = 'a') all: Boolean = false,
      @opt(short = 'q') quiet: Boolean = false,
      @opt(short = 'f') filter: List[String] = Nil,
      @opt(short = 'n') last: Int = -1,
      @opt format: Option[String] = None
  )

final case class MRun(
    name: Option[String],
    env: Seq[String],
    publish: Seq[String],
    volume: Seq[String],
    label: Seq[String],
    workdir: Option[String],
    user: Option[String],
    entrypoint: Option[String],
    network: String,
    restart: String,
    memory: Option[String],
    cpus: Option[Double],
    pull: String,
    detach: Boolean,
    rm: Boolean,
    interactive: Boolean,
    tty: Boolean,
    readOnly: Boolean,
    image: String,
    cmd: Seq[String]
)
final case class MPull(platform: Option[String], quiet: Boolean, allTags: Boolean, image: String)
final case class MPs(
    all: Boolean,
    quiet: Boolean,
    filter: Seq[String],
    last: Int,
    format: Option[String]
)

object MDocker:
  @main def run(
      name: Option[String] = None,
      @arg(short = 'e') env: Seq[String] = Nil,
      @arg(short = 'p') publish: Seq[String] = Nil,
      @arg(short = 'v') volume: Seq[String] = Nil,
      @arg(short = 'l') label: Seq[String] = Nil,
      @arg(short = 'w') workdir: Option[String] = None,
      @arg(short = 'u') user: Option[String] = None,
      entrypoint: Option[String] = None,
      network: String = "default",
      restart: String = "no",
      @arg(short = 'm') memory: Option[String] = None,
      cpus: Option[Double] = None,
      pull: String = "missing",
      @arg(short = 'd') detach: Flag = Flag(),
      rm: Flag = Flag(),
      @arg(short = 'i') interactive: Flag = Flag(),
      @arg(short = 't') tty: Flag = Flag(),
      readOnly: Flag = Flag(),
      // a required positional cannot precede Leftover (leftover capture starts at the first
      // positional token), so image is the leftover's head — as case-app reads it from
      // RemainingArgs
      args: Leftover[String]
  ): MRun = MRun(
    name,
    env,
    publish,
    volume,
    label,
    workdir,
    user,
    entrypoint,
    network,
    restart,
    memory,
    cpus,
    pull,
    detach.value,
    rm.value,
    interactive.value,
    tty.value,
    readOnly.value,
    args.value.head,
    args.value.tail
  )
  @main def pull(
      platform: Option[String] = None,
      @arg(short = 'q') quiet: Flag = Flag(),
      @arg(short = 'a') allTags: Flag = Flag(),
      image: String
  ): MPull = MPull(platform, quiet.value, allTags.value, image)
  @main def ps(
      @arg(short = 'a') all: Flag = Flag(),
      @arg(short = 'q') quiet: Flag = Flag(),
      @arg(short = 'f') filter: Seq[String] = Nil,
      @arg(short = 'n') last: Int = -1,
      format: Option[String] = None
  ): MPs = MPs(all.value, quiet.value, filter, last, format)

final case class CRun(
    name: Option[String] = None,
    @ExtraName("e") env: List[String] = Nil,
    @ExtraName("p") publish: List[String] = Nil,
    @ExtraName("v") volume: List[String] = Nil,
    @ExtraName("l") label: List[String] = Nil,
    @ExtraName("w") workdir: Option[String] = None,
    @ExtraName("u") user: Option[String] = None,
    entrypoint: Option[String] = None,
    network: String = "default",
    restart: String = "no",
    @ExtraName("m") memory: Option[String] = None,
    cpus: Option[Double] = None,
    pull: String = "missing",
    @ExtraName("d") detach: Boolean = false,
    rm: Boolean = false,
    @ExtraName("i") interactive: Boolean = false,
    @ExtraName("t") tty: Boolean = false,
    readOnly: Boolean = false
)
final case class CPull(
    platform: Option[String] = None,
    @ExtraName("q") quiet: Boolean = false,
    @ExtraName("a") allTags: Boolean = false
)
final case class CPs(
    @ExtraName("a") all: Boolean = false,
    @ExtraName("q") quiet: Boolean = false,
    @ExtraName("f") filter: List[String] = Nil,
    @ExtraName("n") last: Int = -1,
    format: Option[String] = None
)

object RealisticDefs:
  val flaggedDocker  = summon[Parser.CommandGroup[FDocker]]
  val mainargsDocker = ParserForMethods(MDocker)

  private val cRun  = CParser[CRun]
  private val cPull = CParser[CPull]
  private val cPs   = CParser[CPs]

  /** First-token dispatch, as case-app's `CommandsEntryPoint` does; image and command land in the
    * remaining args (case-app's positional idiom).
    */
  def caseappDocker(args: Seq[String]): Either[?, ?] = args.head match
    case "run"  => cRun.detailedParse(args.tail)
    case "pull" => cPull.detailedParse(args.tail)
    case "ps"   => cPs.detailedParse(args.tail)
    case other  => sys.error(s"unknown command: $other")

  // expected parses of RuntimeBench's `realistic` command line, for setup validation
  val expectedF: FDocker.Run = FDocker.Run(
    name = Some("web"),
    env = List("PGHOST=db", "PGPORT=5432"),
    publish = List("8080:80", "8443:443"),
    volume = List("/srv/site:/usr/share/nginx/html:ro"),
    label = List("app=web", "env=prod"),
    workdir = Some("/app"),
    user = Some("1000:1000"),
    network = "bridge",
    restart = "on-failure",
    memory = Some("512m"),
    cpus = Some(1.5),
    detach = true,
    rm = true,
    readOnly = true,
    image = "nginx:1.27",
    cmd = List("nginx-debug")
  )
  val expectedM: MRun = MRun(
    expectedF.name,
    expectedF.env,
    expectedF.publish,
    expectedF.volume,
    expectedF.label,
    expectedF.workdir,
    expectedF.user,
    expectedF.entrypoint,
    expectedF.network,
    expectedF.restart,
    expectedF.memory,
    expectedF.cpus,
    expectedF.pull,
    expectedF.detach,
    expectedF.rm,
    expectedF.interactive,
    expectedF.tty,
    expectedF.readOnly,
    expectedF.image,
    expectedF.cmd
  )
  val expectedC: CRun = CRun(
    name = expectedF.name,
    env = expectedF.env,
    publish = expectedF.publish,
    volume = expectedF.volume,
    label = expectedF.label,
    workdir = expectedF.workdir,
    user = expectedF.user,
    network = expectedF.network,
    restart = expectedF.restart,
    memory = expectedF.memory,
    cpus = expectedF.cpus,
    detach = expectedF.detach,
    rm = expectedF.rm,
    readOnly = expectedF.readOnly
  )

  /** All three parse the command line and agree on every field (image and command are checked
    * against case-app's remaining args).
    */
  def agrees(args: Seq[String]): Boolean =
    val f = flaggedDocker.parse(args) match
      case flagged.Result.Ok(v) => v == expectedF
      case _                    => false
    val m = mainargsDocker.runEither(args) == Right(expectedM)
    val c = caseappDocker(args) match
      case Right((opts, rem: caseapp.RemainingArgs)) =>
        opts == expectedC && rem.all == Seq(expectedF.image) ++ expectedF.cmd
      case _ => false
    f && m && c
