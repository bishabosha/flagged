// scala-cli script usage: parse the script's `args` array directly. From the repo root:
//   scala-cli run examples/src/backup.sc flagged/src flagged/src-jvm flagged/src-jvm-native \
//     --dep ch.epfl.lamp::steps::0.2.1 -- --dest /tmp --dry-run src1 src2
import flagged.*

case class Backup(
    @short('d') @help("Destination directory") dest: String,
    @short('n') @help("Print actions without copying") dryRun: Boolean = false,
    @positional @help("Directories to back up") sources: List[String] = Nil
) derives Parser.Command

val cfg  = Flagged.parseOrExit[Backup](args.toSeq, prog = "backup")
val verb = if cfg.dryRun then "Would copy" else "Copying"
cfg.sources.foreach(src => println(s"$verb $src -> ${cfg.dest}"))
