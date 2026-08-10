package examples

import flagged.*

/** Minimal single-command CLI: `greet --name World -e -r 3`. */
@cmd(help = "Greet someone from the command line")
case class Greet(
    @opt(help = "Who to greet", short = 'n') name: String = "world",
    @opt(help = "Add excitement", short = 'e') excited: Boolean = false,
    @opt(help = "How many times to greet", short = 'r') repeat: Int = 1
) derives Parser.Command {
  def run(): Unit =
    val suffix = if excited then "!" else "."
    (1 to repeat).foreach(_ => println(s"Hello, $name$suffix"))
}

@main def greetMain(args: String*): Unit =
  Flagged.parseOrExit[Greet](args).run()
