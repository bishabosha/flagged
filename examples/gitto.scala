package examples

import flagged.*

/** Options shared by several commands: a case class with a Parser splices its options into
  * whichever command declares a field of this type.
  */
case class Output(
    @short('q') @help("Operate quietly") quiet: Boolean = false,
    @short('v') @help("Increase verbosity (repeatable)") verbose: Count = Count(0)
) derives Parser.Command

/** A git-like CLI demonstrating nested subcommands derived from enums. */
@name("gitto")
@help("gitto — a tiny, definitely-not-git version control tool")
enum Gitto derives Parser.Command:
  @help("Clone a repository into a new directory")
  case Clone(
      @positional @help("Repository URL") repo: String,
      @positional @help("Target directory") dir: Option[String] = None,
      @short('d') @help("Create a shallow clone of that depth") depth: Option[Int] = None,
      output: Output = Output()
  )
  @help("Manage the set of tracked repositories")
  case Remote(action: RemoteAction)
  @help("Show the working tree status")
  case Status(
      @short('s') @help("Give output in short format") short: Boolean = false,
      output: Output = Output()
  )

enum RemoteAction derives Parser.Command:
  @help("Add a remote named <name> for the repository at <url>")
  case Add(
      @positional name: String,
      @positional url: String,
      @short('f') @help("Fetch the remote after adding it") fetch: Boolean = false
  )
  @help("Remove the remote named <name>")
  case Remove(@positional name: String)
  @help("List all remotes")
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
