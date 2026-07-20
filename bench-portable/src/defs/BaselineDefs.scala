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
      opt1: String = "",
      opt2: String = "",
      opt3: String = "",
      opt4: String = "",
      opt5: String = "",
      opt6: String = "",
      opt7: String = "",
      opt8: String = "",
      opt9: String = "",
      opt10: String = "",
      opt11: String = "",
      opt12: String = "",
      opt13: String = "",
      opt14: String = "",
      opt15: String = "",
      opt16: String = "",
      opt17: String = "",
      opt18: String = "",
      opt19: String = "",
      opt20: String = "",
      opt21: String = "",
      opt22: String = "",
      opt23: String = "",
      opt24: String = "",
      opt25: String = ""
  )

  /** The same tail-recursive idiom scaled to 25 named options: one exact-string case and one
    * 25-field `copy` per token.
    */
  def naive25(args: List[String]): Either[String, H25] =
    @scala.annotation.tailrec
    def loop(rest: List[String], acc: H25): Either[String, H25] = rest match
      case Nil                     => Right(acc)
      case "--opt-1" :: v :: rest  => loop(rest, acc.copy(opt1 = v))
      case "--opt-2" :: v :: rest  => loop(rest, acc.copy(opt2 = v))
      case "--opt-3" :: v :: rest  => loop(rest, acc.copy(opt3 = v))
      case "--opt-4" :: v :: rest  => loop(rest, acc.copy(opt4 = v))
      case "--opt-5" :: v :: rest  => loop(rest, acc.copy(opt5 = v))
      case "--opt-6" :: v :: rest  => loop(rest, acc.copy(opt6 = v))
      case "--opt-7" :: v :: rest  => loop(rest, acc.copy(opt7 = v))
      case "--opt-8" :: v :: rest  => loop(rest, acc.copy(opt8 = v))
      case "--opt-9" :: v :: rest  => loop(rest, acc.copy(opt9 = v))
      case "--opt-10" :: v :: rest => loop(rest, acc.copy(opt10 = v))
      case "--opt-11" :: v :: rest => loop(rest, acc.copy(opt11 = v))
      case "--opt-12" :: v :: rest => loop(rest, acc.copy(opt12 = v))
      case "--opt-13" :: v :: rest => loop(rest, acc.copy(opt13 = v))
      case "--opt-14" :: v :: rest => loop(rest, acc.copy(opt14 = v))
      case "--opt-15" :: v :: rest => loop(rest, acc.copy(opt15 = v))
      case "--opt-16" :: v :: rest => loop(rest, acc.copy(opt16 = v))
      case "--opt-17" :: v :: rest => loop(rest, acc.copy(opt17 = v))
      case "--opt-18" :: v :: rest => loop(rest, acc.copy(opt18 = v))
      case "--opt-19" :: v :: rest => loop(rest, acc.copy(opt19 = v))
      case "--opt-20" :: v :: rest => loop(rest, acc.copy(opt20 = v))
      case "--opt-21" :: v :: rest => loop(rest, acc.copy(opt21 = v))
      case "--opt-22" :: v :: rest => loop(rest, acc.copy(opt22 = v))
      case "--opt-23" :: v :: rest => loop(rest, acc.copy(opt23 = v))
      case "--opt-24" :: v :: rest => loop(rest, acc.copy(opt24 = v))
      case "--opt-25" :: v :: rest => loop(rest, acc.copy(opt25 = v))
      case other :: _              => Left(s"unknown option: $other")
    loop(args, H25())

  /** What the compiler generates for an `@main` method with 25 `String` parameters. */
  def scalaMain25(args: Array[String]): H25 =
    val a1  = CLP.parseArgument[String](args, 0)
    val a2  = CLP.parseArgument[String](args, 1)
    val a3  = CLP.parseArgument[String](args, 2)
    val a4  = CLP.parseArgument[String](args, 3)
    val a5  = CLP.parseArgument[String](args, 4)
    val a6  = CLP.parseArgument[String](args, 5)
    val a7  = CLP.parseArgument[String](args, 6)
    val a8  = CLP.parseArgument[String](args, 7)
    val a9  = CLP.parseArgument[String](args, 8)
    val a10 = CLP.parseArgument[String](args, 9)
    val a11 = CLP.parseArgument[String](args, 10)
    val a12 = CLP.parseArgument[String](args, 11)
    val a13 = CLP.parseArgument[String](args, 12)
    val a14 = CLP.parseArgument[String](args, 13)
    val a15 = CLP.parseArgument[String](args, 14)
    val a16 = CLP.parseArgument[String](args, 15)
    val a17 = CLP.parseArgument[String](args, 16)
    val a18 = CLP.parseArgument[String](args, 17)
    val a19 = CLP.parseArgument[String](args, 18)
    val a20 = CLP.parseArgument[String](args, 19)
    val a21 = CLP.parseArgument[String](args, 20)
    val a22 = CLP.parseArgument[String](args, 21)
    val a23 = CLP.parseArgument[String](args, 22)
    val a24 = CLP.parseArgument[String](args, 23)
    val a25 = CLP.parseArgument[String](args, 24)
    H25(
      a1,
      a2,
      a3,
      a4,
      a5,
      a6,
      a7,
      a8,
      a9,
      a10,
      a11,
      a12,
      a13,
      a14,
      a15,
      a16,
      a17,
      a18,
      a19,
      a20,
      a21,
      a22,
      a23,
      a24,
      a25
    )

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
