package examples

import flagged.*

/** Minimal single-command CLI: `greet --name World -e -r 3`. */
@help("Greet someone from the command line")
case class Greet(
    @short('n') @help("Who to greet") name: String = "world",
    @short('e') @help("Add excitement") excited: Boolean = false,
    @short('r') @help("How many times to greet") repeat: Int = 1
) derives Parser.Command {
  def run(): Unit =
    val suffix = if excited then "!" else "."
    (1 to repeat).foreach(_ => println(s"Hello, $name$suffix"))
}

@main def greetMain(args: String*): Unit =
  Flagged.parseOrExit[Greet](args).run()
