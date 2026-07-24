package bench

/** Micro-probe: the per-evaluation cost of the field-rule primitives, isolated. Compiles a file
  * with N identical `constValue` sites per variant against the current flagged jar (so it measures
  * whatever encoding is in the working tree) with the warm driver, best of 5.
  *
  * Findings (Scala 3.8.3) that shaped `Derive.FieldErr`'s single-pass encoding: match-type verdicts
  * cache across sites within a compilation unit (costs are flat in N), so what matters is the cost
  * of ONE reduction — and that cost is set by what sits in scrutinee position. A scrutinee is
  * reduced strictly, and nested inside an outer reduction it is re-reduced without memoization:
  * presence-query scrutinees (`HasAnnT[...] match`) cost ~7-13 ms per file, ops-based ones
  * (`BitwiseAnd[...] != 0`, or ops behind further match layers) 60-190 ms, while data-only
  * scrutinees (the slot tuple, destructured elements, literal parameters) reduce at noise level.
  * Computation belongs in arms; data belongs in scrutinees.
  *
  * ./mill bench.runMain bench.RuleCostProbe
  */
object RuleCostProbe:

  def src(n: Int, expr: String): String =
    val vals = (1 to n).map(i => s"  val v$i = compiletime.constValue[$expr]").mkString("\n")
    s"""package flagged.probe // subpackage of `flagged`: the probe exercises private[flagged] internals
       |import flagged.internal.Derive.*
       |import scala.compiletime
       |import scala.compiletime.ops.any.{==, !=}
       |import scala.compiletime.ops.int.{BitwiseAnd, BitwiseOr}
       |object Use:
       |$vals
       |""".stripMargin

  val variants: List[(String, String)] = List(
    "floor (constValue[1])" -> "1",
    "HasAnnT empty"         -> "HasAnnT[flagged.version, EmptyTuple]",
    "FieldErr full query"   -> "FieldErr[1, EmptyTuple, false] == \"\"",
    "merge gate (ops, lit)" -> "BitwiseOr[0, 0] == 0",
    "hasBit (ops, lit)"     -> "BitwiseAnd[5, 1] != 0"
  )

  /** A nested BitwiseOr tree of the given leaf count, as merge would build it. */
  def orTree(n: Int): String =
    if n == 1 then "0" else s"BitwiseOr[${orTree(n / 2)}, ${orTree(n - n / 2)}]"

  def main(args: Array[String]): Unit =
    val n = args.headOption.map(_.toInt).getOrElse(200)
    (1 to 3).foreach(_ => ScalingProbe.compileOnce(src(n, "1")))
    println(f"${"variant"}%-24s ms (n=$n%d sites, best of 5)")
    for d <- List(8, 32, 128) do
      try
        val t = (1 to 5).map(_ => ScalingProbe.compileOnce(src(1, s"${orTree(d)} == 0"))).min
        println(f"or-tree depth $d%-3d (n=1)   $t%8.1f")
      catch case _: Throwable => println(f"or-tree depth $d%-3d      n/a")
    for (name, e) <- variants do
      try
        val t = (1 to 5).map(_ => ScalingProbe.compileOnce(src(n, e))).min
        println(f"$name%-24s $t%8.1f")
      catch case _: Throwable => println(f"$name%-24s      n/a")
