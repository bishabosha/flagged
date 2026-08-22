// scala-cli script usage: parse the script's `args` array directly. From the repo root:
//   ./mill flagged.jvm.compile && scala-cli run examples/src/backup.sc -- --dest /tmp --dry-run src1 src2
//> using scala 3.9.0-RC4
//> using dep ch.epfl.lamp::steps::0.2.1
//> using jars ../../out/flagged/jvm/compile.dest/classes
import flagged.*

case class Backup(
    @opt(help = "Destination directory", short = 'd') dest: String,
    @opt(help = "Print actions without copying", short = 'n') dryRun: Boolean = false,
    @opt(help = "Directories to back up", positional = true) sources: List[String] = Nil
) derives Parser.Command

val cfg  = Flagged.parseOrExit[Backup](args.toSeq, prog = "backup")
val verb = if cfg.dryRun then "Would copy" else "Copying"
cfg.sources.foreach(src => println(s"$verb $src -> ${cfg.dest}"))
