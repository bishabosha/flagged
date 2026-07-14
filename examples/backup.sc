// scala-cli script usage: parse the script's `args` array directly.
//   scala-cli run . --main-class examples.backup_sc -- --dest /tmp --dry-run src1 src2
import claw.*

case class Backup(
    @short('d') @help("Destination directory") dest: String,
    @short('n') @help("Print actions without copying") dryRun: Boolean = false,
    @positional @help("Directories to back up") sources: List[String] = Nil
) derives Parser

val cfg = Claw.parseOrExit[Backup](args.toSeq, prog = "backup")
val verb = if cfg.dryRun then "Would copy" else "Copying"
cfg.sources.foreach(src => println(s"$verb $src -> ${cfg.dest}"))
