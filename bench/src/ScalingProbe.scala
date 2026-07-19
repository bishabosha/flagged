package bench

import java.nio.file.Files
import dotty.tools.dotc.Driver
import dotty.tools.dotc.reporting.StoreReporter

/** Not a JMH benchmark: a quick scaling curve for derivation compile time by field count. Compiles
  * a generated N-field options class per library, warm in-process, best of `reps`.
  *
  * scala-cli --power run bench --main-class bench.ScalingProbe
  */
object ScalingProbe:

  def flaggedSrc(n: Int, annotated: Boolean): String =
    val fields = (1 to n)
      .map { i =>
        val ann = if annotated && i % 2 == 0 then s"@name(\"renamed-$i\") " else ""
        s"    ${ann}f$i: Int = $i"
      }
      .mkString(",\n")
    s"""import flagged.*
       |case class Wide(
       |$fields
       |) derives Parser.Command
       |object Use { val parser = summon[Parser.Command[Wide]] }
       |""".stripMargin

  def mainargsSrc(n: Int): String =
    val fields = (1 to n).map(i => s"    f$i: Int = $i").mkString(",\n")
    s"""import mainargs.{main, ParserForClass}
       |@main case class Wide(
       |$fields
       |)
       |object Use { val parser = ParserForClass[Wide] }
       |""".stripMargin

  def compileOnce(src: String): Double = compileOnce(src, raiseLimit = true)

  def compileOnce(src: String, raiseLimit: Boolean): Double =
    val dir  = Files.createTempDirectory("scaling")
    val file = dir.resolve("Wide.scala")
    Files.writeString(file, src)
    val out   = Files.createDirectories(dir.resolve("out"))
    val limit = if raiseLimit then Array("-Xmax-inlines:1000000") else Array.empty[String]
    val args  = Array(
      "-d",
      out.toString,
      "-classpath",
      sys.props("java.class.path"),
      "-nowarn"
    ) ++ limit :+ file.toString
    val t0  = System.nanoTime()
    val rep = new Driver().process(args, new StoreReporter())
    val ms  = (System.nanoTime() - t0) / 1e6
    if rep.hasErrors then throw new IllegalStateException(s"compile failed:\n$src")
    ms

  def best(src: String, reps: Int = 5): Double =
    (1 to reps).map(_ => compileOnce(src)).min

  def main(args: Array[String]): Unit =
    val sizes = List(4, 8, 16, 32, 64, 128)
    // warm the compiler thoroughly
    (1 to 6).foreach(_ => compileOnce(flaggedSrc(8, annotated = false)))
    (1 to 6).foreach(_ => compileOnce(mainargsSrc(8)))
    println(f"${"n"}%4s ${"flagged"}%10s ${"flagged@"}%10s ${"mainargs"}%10s   (ms, best of 5)")
    sizes.foreach { n =>
      val f  = best(flaggedSrc(n, annotated = false))
      val fa = best(flaggedSrc(n, annotated = true))
      val m  = best(mainargsSrc(n))
      println(f"$n%4d $f%10.1f $fa%10.1f $m%10.1f")
    }
    // default -Xmax-inlines: the halved walks must stay within the standard limit
    compileOnce(flaggedSrc(64, annotated = true), raiseLimit = false)
    println("64 annotated fields compile at the default -Xmax-inlines")

/** Compiles the 64-field annotated class in a loop, for profiling the compiler under JFR:
  *
  * scala-cli --power run bench --main-class bench.ProfileProbe \ --java-opt
  * -XX:StartFlightRecording=filename=derive.jfr jfr view hot-methods derive.jfr
  */
object ProfileProbe:
  def main(args: Array[String]): Unit =
    val src = ScalingProbe.flaggedSrc(64, annotated = true)
    (1 to 5).foreach(_ => ScalingProbe.compileOnce(src)) // warm
    val t0 = System.nanoTime()
    var n  = 0
    while (System.nanoTime() - t0) < 60e9 do
      ScalingProbe.compileOnce(src)
      n += 1
    println(s"$n compilations in 60s")
