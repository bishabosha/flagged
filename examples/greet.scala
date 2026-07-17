package examples

import flagged.*

/** Minimal single-command CLI: `greet --name World -e -r 3`. */
@help("Greet someone from the command line")
case class Greet(
    @short('n') @help("Who to greet") name: String = "world",
    @short('e') @help("Add excitement") excited: Boolean = false,
    @short('r') @help("How many times to greet") repeat: Int = 1
) derives Parser

@main def greetMain(args: String*): Unit =
  val cfg = Flagged.parseOrExit[Greet](args)
  val suffix = if cfg.excited then "!" else "."
  (1 to cfg.repeat).foreach(_ => println(s"Hello, ${cfg.name}$suffix"))
