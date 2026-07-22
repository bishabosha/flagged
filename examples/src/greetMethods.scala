package examples.methods

import flagged.*

/** Minimal single-command CLI: `greet --name World -e -r 3`. */
@run
@help("Greet someone from the command line")
def greet(
    @short('n') @help("Who to greet") name: String = "world",
    @short('e') @help("Add excitement") excited: Boolean = false,
    @short('r') @help("How many times to greet") repeat: Int = 1
): Unit = {
  val suffix = if excited then "!" else "."
  (1 to repeat).foreach(_ => println(s"Hello, $name$suffix"))
}

@main def main(args: String*): Unit =
  Flagged.parseOrExit[this.type](args)
