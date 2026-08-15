package examples

import flagged.*

/** Options shared by several commands: a case class with a Parser splices its options into
  * whichever command declares a field of this type.
  */
case class Output(
    @opt(help = "Operate quietly", short = 'q') quiet: Boolean = false,
    @opt(help = "Increase verbosity (repeatable)", short = 'v') verbose: Count = Count(0)
) derives Parser.Shared

/** A git-like CLI demonstrating nested subcommands derived from enums. */
@cmd(name = "gitto", help = "gitto — a tiny, definitely-not-git version control tool")
enum Gitto derives Parser.CommandGroup:
  @cmd(help = "Clone a repository into a new directory")
  case Clone(
      @opt(help = "Repository URL", positional = true) repo: String,
      @opt(help = "Target directory", positional = true) dir: Option[String] = None,
      @opt(help = "Create a shallow clone of that depth", short = 'd') depth: Option[Int] = None,
      output: Output = Output()
  )
  @cmd(help = "Manage the set of tracked repositories")
  case Remote(action: RemoteAction)
  @cmd(help = "Show the working tree status")
  case Status(
      @opt(help = "Give output in short format", short = 's') short: Boolean = false,
      output: Output = Output()
  )

enum RemoteAction derives Parser.CommandGroup:
  @cmd(help = "Add a remote named <name> for the repository at <url>")
  case Add(
      // unannotated fields are positional by default, like @main parameters
      name: String,
      url: String,
      @opt(help = "Fetch the remote after adding it", short = 'f') fetch: Boolean = false
  )
  @cmd(help = "Remove the remote named <name>")
  case Remove(name: String)
  @cmd(help = "List all remotes")
  case ListAll

@main def gittoMain(args: String*): Unit =
  Flagged.parseOrExit[Gitto](args) match
    case Gitto.Clone(repo, dir, depth, out) =>
      val where = dir.getOrElse(repo.split('/').last.stripSuffix(".git"))
      val how   = depth.map(d => s" (depth $d)").getOrElse("")
      if out.verbose.value > 1 then println(s"[debug] resolved target directory: $where")
      if !out.quiet then println(s"Cloning '$repo' into '$where'$how ...")
    case Gitto.Remote(RemoteAction.Add(name, url, fetch)) =>
      println(s"Added remote '$name' -> $url${if fetch then " (fetched)" else ""}")
    case Gitto.Remote(RemoteAction.Remove(name)) =>
      println(s"Removed remote '$name'")
    case Gitto.Remote(RemoteAction.ListAll) =>
      println("origin\nupstream")
    case Gitto.Status(short, out) =>
      if !out.quiet then
        println(if short then "## main...origin/main" else "On branch main\nnothing to commit")
