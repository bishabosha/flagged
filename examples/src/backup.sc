// scala-cli script usage: parse the script's `args` array directly. From the repo root:
//   ./mill flagged.jvm.compile && scala-cli run examples/src/backup.sc -- --dest /tmp --dry-run src1 src2
//> using scala 3.8.3
//> using dep ch.epfl.lamp::steps::0.2.1
//> using jars ../../out/flagged/jvm/compile.dest/classes
import flagged.*

case class Backup(
    @short('d') @help("Destination directory") dest: String,
    @short('n') @help("Print actions without copying") dryRun: Boolean = false,
    @positional @help("Directories to back up") sources: List[String] = Nil
) derives Parser.Command

val cfg  = Flagged.parseOrExit[Backup](args.toSeq, prog = "backup")
val verb = if cfg.dryRun then "Would copy" else "Copying"
cfg.sources.foreach(src => println(s"$verb $src -> ${cfg.dest}"))
