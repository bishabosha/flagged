package flagged.internal

import flagged.Parser
import steps.result.Result
import steps.result.Result.eval

/** Runtime helpers referenced by derivation. Not intended for direct use. */
object Runtime:

  /** 1 = true, 0 = false, -1 = not a recognized spelling. */
  private def boolOf(s: String): Int =
    s.trim.toLowerCase match
      case "true" | "yes" | "on" | "1"  => 1
      case "false" | "no" | "off" | "0" => 0
      case _                            => -1

  private def notABool(s: String): String =
    s"'${s.trim.toLowerCase}' is not a valid bool (expected true/false)"

  def parseBool(s: String): Result[Boolean, String] =
    Result:
      val b = boolOf(s)
      if b < 0 then eval.raise(notABool(s))
      b == 1

  def parseBoolInto(s: String, out: Array[Any], i: Int): Result[Unit, String] =
    Result.task:
      val b = boolOf(s)
      if b < 0 then eval.raise(notABool(s))
      out(i) = b == 1

  /** Value parser for enums whose cases are all parameterless, matching kebab-cased names. */
  def enumParser[A](name: String, pairs: Vector[(String, A)]): Parser.Enumerated[A] =
    Parser.enumeratedOf(name, pairs)

  private def levenshtein(a: String, b: String): Int =
    val d = Array.tabulate(a.length + 1, b.length + 1) { (i, j) =>
      if i == 0 then j else if j == 0 then i else 0
    }
    for i <- 1 to a.length; j <- 1 to b.length do
      val cost = if a(i - 1) == b(j - 1) then 0 else 1
      d(i)(j) = math.min(math.min(d(i - 1)(j) + 1, d(i)(j - 1) + 1), d(i - 1)(j - 1) + cost)
    d(a.length)(b.length)

  /** Closest candidate within edit distance 2, for "did you mean" hints. */
  def suggest(input: String, candidates: Iterable[String]): Option[String] =
    candidates
      .map(c => c -> levenshtein(input.toLowerCase, c.toLowerCase))
      .filter((c, d) => d <= 2 && d < c.length)
      .minByOption(_(1))
      .map(_(0))
