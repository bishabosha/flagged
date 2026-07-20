package bench.defs

import scala.util.CommandLineParser as CLP

/** Non-library baselines for the `simple` grammar (`--foo <string>`, `--bar <int>`, `--baz`,
  * repeatable `--qux <string>`): what hand-rolling a parser costs, at two levels of fidelity, plus
  * the Scala `@main` built-in machinery.
  */
object BaselineDefs:

  final case class HSimple(foo: String, bar: Int, baz: Boolean, qux: List[String])

  /** The typical quick hand-rolled parser: a cursor loop matching long options only — no short
    * options, no `=` forms, no suggestions, first error wins.
    */
  def naive(args: Seq[String]): Either[String, HSimple] =
    var foo             = "x"
    var bar             = 0
    var baz             = false
    val qux             = List.newBuilder[String]
    val it              = args.iterator
    var err: String     = null
    def take(d: String) =
      if it.hasNext then it.next()
      else
        err = s"missing value for $d"
        null
    while it.hasNext && err == null do
      it.next() match
        case "--foo" => val v = take("--foo"); if v != null then foo = v
        case "--bar" =>
          val v = take("--bar")
          if v != null then
            try bar = v.toInt
            catch case _: NumberFormatException => err = s"invalid int for --bar: $v"
        case "--baz" => baz = true
        case "--qux" => val v = take("--qux"); if v != null then qux += v
        case other   => err = s"unknown option: $other"
    if err != null then Left(err) else Right(HSimple(foo, bar, baz, qux.result()))

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
