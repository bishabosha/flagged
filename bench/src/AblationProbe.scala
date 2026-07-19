package bench

/** Compile-time ablation at a fixed field count: which parts of derivation cost what.
  *
  * scala-cli --power run bench --main-class bench.AblationProbe
  */
object AblationProbe:

  def fields(n: Int): String =
    (1 to n).map(i => s"    f$i: Int = $i").mkString(",\n")

  def src(n: Int, use: String): String =
    s"""import flagged.*
       |import flagged.internal.{Derive, Annots}
       |import flagged.meta.{AnnotMirror, Defaults}
       |import scala.deriving.Mirror
       |import scala.compiletime.summonFrom
       |case class Wide(
       |${fields(n)}
       |)
       |object Use:
       |$use
       |""".stripMargin

  val variants: List[(String, String)] = List(
    "nothing (floor)" -> "  val x = Wide()",
    "mirror summon"   -> "  val x = summon[Mirror.ProductOf[Wide]]",
    "annot mirror" -> "  inline def p[A]: Any = summonFrom { case am: AnnotMirror.Product[A] => am }\n  val x = p[Wide]",
    "annots extract" -> "  val x = Annots.productAnnots[Wide]",
    "defaults"       -> "  val x = Defaults.derived[Wide]",
    "labels" -> "  inline def p[A](using m: Mirror.ProductOf[A]): Any = Derive.labelsOf[m.MirroredElemLabels]\n  val x = p[Wide]",
    "field walk" ->
      """  inline def p[A](using m: Mirror.ProductOf[A]): Any = summonFrom {
        |    case am: AnnotMirror.Product[A] =>
        |      Derive.fieldsOf[m.MirroredElemTypes, am.MirroredAnnotations]
        |  }
        |  val x = p[Wide]""".stripMargin,
    "full derivation" -> "  val x = Parser.Command.derived[Wide]",
    "walk: skeleton1" -> w(
      """  inline def w1[Types <: Tuple]: List[Any] =
        |    inline compiletime.erasedValue[Types] match
        |      case _: EmptyTuple        => Nil
        |      case _: (? *: EmptyTuple) => Nil
        |      case _: NonEmptyTuple     =>
        |        w1[Tuple.Take[Types, Half[Types]]] ::: w1[Tuple.Drop[Types, Half[Types]]]
        |  inline def go(using m: Mirror.ProductOf[Wide]): List[Any] = w1[m.MirroredElemTypes]
        |  val x = go""".stripMargin
    ),
    "walk: skeleton3" -> w(
      """  inline def w1[Types <: Tuple, Slots <: Tuple, Labels <: Tuple]: List[Any] =
        |    inline compiletime.erasedValue[Types] match
        |      case _: EmptyTuple        => Nil
        |      case _: (? *: EmptyTuple) => Nil
        |      case _: NonEmptyTuple     =>
        |        w1[Tuple.Take[Types, Half[Types]], Tuple.Take[Slots, Half[Types]], Tuple.Take[Labels, Half[Types]]] :::
        |          w1[Tuple.Drop[Types, Half[Types]], Tuple.Drop[Slots, Half[Types]], Tuple.Drop[Labels, Half[Types]]]
        |  inline def go(using m: Mirror.ProductOf[Wide]): List[Any] =
        |    summonFrom { case am: AnnotMirror.Product[Wide] =>
        |      w1[m.MirroredElemTypes, am.MirroredAnnotations, m.MirroredElemLabels] }
        |  val x = go""".stripMargin
    ),
    "walk: + summon" -> w(
      """  inline def w1[Types <: Tuple]: List[Any] =
        |    inline compiletime.erasedValue[Types] match
        |      case _: EmptyTuple        => Nil
        |      case _: (? *: EmptyTuple) =>
        |        (compiletime.summonInline[Parser[Tuple.Head[Types & NonEmptyTuple]]], false) :: Nil
        |      case _: NonEmptyTuple     =>
        |        w1[Tuple.Take[Types, Half[Types]]] ::: w1[Tuple.Drop[Types, Half[Types]]]
        |  inline def go(using m: Mirror.ProductOf[Wide]): List[Any] = w1[m.MirroredElemTypes]
        |  val x = go""".stripMargin
    ),
    "walk: + dispatch" -> w(
      """  inline def w1[Types <: Tuple]: List[Any] =
        |    inline compiletime.erasedValue[Types] match
        |      case _: EmptyTuple        => Nil
        |      case _: (? *: EmptyTuple) =>
        |        summonFrom { case p: Parser[Tuple.Head[Types & NonEmptyTuple]] =>
        |          inline p match
        |            case _: Parser.ValuedFlag[?]   => (p, false) :: Nil
        |            case _: Parser.Flag[?]         => (p, false) :: Nil
        |            case _: Parser.Value[?]        => (p, false) :: Nil
        |            case _: Parser.Repeated[?]     => (p, false) :: Nil
        |            case _: Parser.Trailing[?]     => (p, false) :: Nil
        |            case _: Parser.CommandGroup[?] => (p, false) :: Nil
        |            case _: Parser.Command[?]      => (p, false) :: Nil
        |            case _                         => compiletime.error("shape")
        |        }
        |      case _: NonEmptyTuple =>
        |        w1[Tuple.Take[Types, Half[Types]]] ::: w1[Tuple.Drop[Types, Half[Types]]]
        |  inline def go(using m: Mirror.ProductOf[Wide]): List[Any] = w1[m.MirroredElemTypes]
        |  val x = go""".stripMargin
    )
  )

  def moreVariantsPublic: List[(String, String)] = moreVariants

  private val moreVariants: List[(String, String)] = List(
    "skl3 rebind" -> w(
      """  inline def w1[Types <: Tuple, Slots <: Tuple]: List[Any] =
        |    inline compiletime.erasedValue[Types] match
        |      case _: EmptyTuple        => Nil
        |      case _: (? *: EmptyTuple) => Nil
        |      case _: NonEmptyTuple     =>
        |        inline compiletime.erasedValue[Tuple.Take[Types, Half[Types]]] match
        |          case _: (lt0 *: ltr) =>
        |            inline compiletime.erasedValue[Tuple.Take[Slots, Half[Types]]] match
        |              case _: (ls0 *: lsr) =>
        |                inline compiletime.erasedValue[Tuple.Drop[Types, Half[Types]]] match
        |                  case _: (rt0 *: rtr) =>
        |                    inline compiletime.erasedValue[Tuple.Drop[Slots, Half[Types]]] match
        |                      case _: (rs0 *: rsr) =>
        |                        w1[lt0 *: ltr, ls0 *: lsr] ::: w1[rt0 *: rtr, rs0 *: rsr]
        |  inline def go(using m: Mirror.ProductOf[Wide]): List[Any] =
        |    summonFrom { case am: AnnotMirror.Product[Wide] =>
        |      inline compiletime.erasedValue[m.MirroredElemTypes] match
        |        case _: (t0 *: tr) =>
        |          inline compiletime.erasedValue[am.MirroredAnnotations] match
        |            case _: (s0 *: sr) => w1[t0 *: tr, s0 *: sr] }
        |  val x = go""".stripMargin
    ),
    "skl1 unroll4" -> w(
      """  inline def w1[Types <: Tuple]: List[Any] =
        |    inline compiletime.erasedValue[Types] match
        |      case _: EmptyTuple                          => Nil
        |      case _: (? *: EmptyTuple)                   => null :: Nil
        |      case _: (? *: ? *: EmptyTuple)              => null :: null :: Nil
        |      case _: (? *: ? *: ? *: EmptyTuple)         => null :: null :: null :: Nil
        |      case _: (? *: ? *: ? *: ? *: EmptyTuple)    => null :: null :: null :: null :: Nil
        |      case _: NonEmptyTuple                       =>
        |        w1[Tuple.Take[Types, Half[Types]]] ::: w1[Tuple.Drop[Types, Half[Types]]]
        |  inline def go(using m: Mirror.ProductOf[Wide]): List[Any] = w1[m.MirroredElemTypes]
        |  val x = go""".stripMargin
    ),
    "transparent x1/leaf" -> w(
      """  class Res(val fields: List[Any]):
        |    type S <: Boolean
        |  type ResOf[B <: Boolean] = Res { type S = B }
        |  inline def res[B <: Boolean](fs: List[Any]): ResOf[B] = new Res(fs).asInstanceOf[ResOf[B]]
        |  transparent inline def w1[Types <: Tuple, S0 <: Boolean]: Res =
        |    inline compiletime.erasedValue[Types] match
        |      case _: EmptyTuple        => res[S0](Nil)
        |      case _: (? *: EmptyTuple) =>
        |        summonFrom { case p: Parser[Tuple.Head[Types & NonEmptyTuple]] =>
        |          inline p match
        |            case _: Parser.Value[?] => res[S0]((p, false) :: Nil)
        |            case _                  => res[true]((p, false) :: Nil)
        |        }
        |      case _: NonEmptyTuple =>
        |        w1[Tuple.Take[Types, Half[Types]], S0].seal[Types]
        |  extension [L <: Res](l: L)
        |    transparent inline def seal[Types <: Tuple]: Res =
        |      fin(l, w1[Tuple.Drop[Types, Half[Types]], l.S])
        |  transparent inline def fin[L <: Res, R <: Res](l: L, r: R): Res =
        |    res[r.S](l.fields ++ r.fields)
        |  inline def go(using m: Mirror.ProductOf[Wide]): List[Any] =
        |    w1[m.MirroredElemTypes, false].fields
        |  val x = go""".stripMargin
    )
  )

  private def w(body: String): String =
    "  import scala.compiletime.ops.int./\n  type Half[T <: Tuple] = Tuple.Size[T] / 2\n" + body

  def main(args: Array[String]): Unit =
    val n = args.headOption.map(_.toInt).getOrElse(128)
    // warm
    (1 to 4).foreach(_ => ScalingProbe.compileOnce(src(n, variants.last._2)))
    (variants ++ moreVariants).foreach { (name, use) =>
      val t = (1 to 5).map(_ => ScalingProbe.compileOnce(src(n, use))).min
      println(f"$name%16s $t%8.1f ms")
    }
