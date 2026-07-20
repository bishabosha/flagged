package bench.defs

import scala.util.CommandLineParser as CLP

/** Non-library baselines for the `simple` grammar (`--foo <string>`, `--bar <int>`, `--baz`,
  * repeatable `--qux <string>`): what hand-rolling a parser costs, at two levels of fidelity, plus
  * the Scala `@main` built-in machinery.
  */
object BaselineDefs:

  final case class HSimple(foo: String, bar: Int, baz: Boolean, qux: List[String])

  /** The typical quick hand-rolled parser: a tail-recursive match over the token list, matching
    * exact option strings and copying an accumulator — long options only, no `=` forms, no
    * suggestions, first error wins.
    */
  def naive(args: List[String]): Either[String, HSimple] =
    @scala.annotation.tailrec
    def loop(rest: List[String], acc: HSimple): Either[String, HSimple] = rest match
      case Nil                  => Right(acc)
      case "--foo" :: v :: rest => loop(rest, acc.copy(foo = v))
      case "--bar" :: v :: rest =>
        v.toIntOption match
          case Some(n) => loop(rest, acc.copy(bar = n))
          case None    => Left(s"invalid int for --bar: $v")
      case "--baz" :: rest      => loop(rest, acc.copy(baz = true))
      case "--qux" :: v :: rest => loop(rest, acc.copy(qux = acc.qux :+ v))
      case other :: _           => Left(s"unknown option: $other")
    loop(args, HSimple("x", 0, false, Nil))

  /** A hand-rolled parser at feature parity with the libraries on this grammar: long options with
    * `--opt=v`, short options `-f`/`-b` with clusters and attached values, last-wins repetition,
    * and error accumulation. No help, suggestions, or derivation — those don't cost parse time.
    */
  def full(args: Seq[String]): Either[List[String], HSimple] =
    var foo                           = "x"
    var bar                           = 0
    var baz                           = false
    val qux                           = List.newBuilder[String]
    var errs: List[String]            = Nil
    def err(m: String)                = errs = m :: errs
    var i                             = 0
    val n                             = args.length
    def take(display: String): String =
      if i < n && !(args(i).length > 1 && args(i).charAt(0) == '-') then
        val v = args(i)
        i += 1
        v
      else
        err(s"option '$display' requires a value")
        null
    def setBar(v: String, display: String): Unit =
      if v != null then
        try bar = v.toInt
        catch
          case _: NumberFormatException =>
            err(s"invalid value for '$display': '$v' is not a valid int")
    while i < n do
      val tok = args(i)
      i += 1
      if tok.startsWith("--") then
        val eq               = tok.indexOf('=')
        val key              = if eq == -1 then tok else tok.substring(0, eq)
        def value(d: String) = if eq == -1 then take(d) else tok.substring(eq + 1)
        key match
          case "--foo" => val v = value("--foo"); if v != null then foo = v
          case "--bar" => setBar(value("--bar"), "--bar")
          case "--baz" => baz = eq == -1 || tok.substring(eq + 1) == "true"
          case "--qux" => val v = value("--qux"); if v != null then qux += v
          case _       => err(s"unknown option '$key'")
      else if tok.length > 1 && tok.charAt(0) == '-' then
        var j    = 1
        var stop = false
        while j < tok.length && !stop do
          tok.charAt(j) match
            case 'b' =>
              baz = true
              j += 1
            case 'f' =>
              val v =
                if j + 1 < tok.length then
                  if tok.charAt(j + 1) == '=' then tok.substring(j + 2) else tok.substring(j + 1)
                else take("-f")
              if v != null then foo = v
              stop = true
            case c =>
              err(s"unknown option '-$c'")
              stop = true
      else err(s"unexpected argument '$tok'")
    if errs.nonEmpty then Left(errs.reverse) else Right(HSimple(foo, bar, baz, qux.result()))

  final case class H25(
      opt1: Int = 0,
      opt2: Int = 0,
      opt3: Int = 0,
      opt4: Int = 0,
      opt5: Int = 0,
      opt6: Int = 0,
      opt7: Int = 0,
      opt8: Int = 0,
      opt9: Int = 0,
      opt10: Int = 0,
      opt11: Int = 0,
      opt12: Int = 0,
      opt13: Int = 0,
      opt14: Int = 0,
      opt15: Int = 0,
      opt16: Int = 0,
      opt17: Int = 0,
      opt18: Int = 0,
      opt19: Int = 0,
      opt20: Int = 0,
      opt21: Int = 0,
      opt22: Int = 0,
      opt23: Int = 0,
      opt24: Int = 0,
      opt25: Int = 0
  )

  /** The same tail-recursive idiom scaled to 25 named options: one exact-string case and one
    * 25-field `copy` per token (invalid numbers just throw, as quick code does).
    */
  def naive25(args: List[String]): Either[String, H25] =
    @scala.annotation.tailrec
    def loop(rest: List[String], acc: H25): Either[String, H25] = rest match
      case Nil                     => Right(acc)
      case "--opt-1" :: v :: rest  => loop(rest, acc.copy(opt1 = v.toInt))
      case "--opt-2" :: v :: rest  => loop(rest, acc.copy(opt2 = v.toInt))
      case "--opt-3" :: v :: rest  => loop(rest, acc.copy(opt3 = v.toInt))
      case "--opt-4" :: v :: rest  => loop(rest, acc.copy(opt4 = v.toInt))
      case "--opt-5" :: v :: rest  => loop(rest, acc.copy(opt5 = v.toInt))
      case "--opt-6" :: v :: rest  => loop(rest, acc.copy(opt6 = v.toInt))
      case "--opt-7" :: v :: rest  => loop(rest, acc.copy(opt7 = v.toInt))
      case "--opt-8" :: v :: rest  => loop(rest, acc.copy(opt8 = v.toInt))
      case "--opt-9" :: v :: rest  => loop(rest, acc.copy(opt9 = v.toInt))
      case "--opt-10" :: v :: rest => loop(rest, acc.copy(opt10 = v.toInt))
      case "--opt-11" :: v :: rest => loop(rest, acc.copy(opt11 = v.toInt))
      case "--opt-12" :: v :: rest => loop(rest, acc.copy(opt12 = v.toInt))
      case "--opt-13" :: v :: rest => loop(rest, acc.copy(opt13 = v.toInt))
      case "--opt-14" :: v :: rest => loop(rest, acc.copy(opt14 = v.toInt))
      case "--opt-15" :: v :: rest => loop(rest, acc.copy(opt15 = v.toInt))
      case "--opt-16" :: v :: rest => loop(rest, acc.copy(opt16 = v.toInt))
      case "--opt-17" :: v :: rest => loop(rest, acc.copy(opt17 = v.toInt))
      case "--opt-18" :: v :: rest => loop(rest, acc.copy(opt18 = v.toInt))
      case "--opt-19" :: v :: rest => loop(rest, acc.copy(opt19 = v.toInt))
      case "--opt-20" :: v :: rest => loop(rest, acc.copy(opt20 = v.toInt))
      case "--opt-21" :: v :: rest => loop(rest, acc.copy(opt21 = v.toInt))
      case "--opt-22" :: v :: rest => loop(rest, acc.copy(opt22 = v.toInt))
      case "--opt-23" :: v :: rest => loop(rest, acc.copy(opt23 = v.toInt))
      case "--opt-24" :: v :: rest => loop(rest, acc.copy(opt24 = v.toInt))
      case "--opt-25" :: v :: rest => loop(rest, acc.copy(opt25 = v.toInt))
      case other :: _              => Left(s"unknown option: $other")
    loop(args, H25())

  /** What the compiler generates for `@main def run(foo: String, bar: Int, baz: Boolean, qux:
    * String*)`. The built-in support has no named options at all, so it parses the same data
    * positionally — less work than any option grammar.
    */
  def scalaMain(args: Array[String]): HSimple =
    val foo = CLP.parseArgument[String](args, 0)
    val bar = CLP.parseArgument[Int](args, 1)
    val baz = CLP.parseArgument[Boolean](args, 2)
    val qux = CLP.parseRemainingArguments[String](args, 3)
    HSimple(foo, bar, baz, qux)
