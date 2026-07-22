package examples

import flagged.*

/** A required option, a flag, and repeated positionals. The body of a scala-cli script parsing the
  * script's `args` looks the same, minus the `@main` wrapper — see "Scripts and other entry points"
  * in the README.
  */
case class Backup(
    @short('d') @help("Destination directory") dest: String,
    @short('n') @help("Print actions without copying") dryRun: Boolean = false,
    @positional @help("Directories to back up") sources: List[String] = Nil
) derives Parser.Command

@main def backupMain(args: String*): Unit =
  val cfg  = Flagged.parseOrExit[Backup](args, prog = "backup")
  val verb = if cfg.dryRun then "Would copy" else "Copying"
  cfg.sources.foreach(src => println(s"$verb $src -> ${cfg.dest}"))
