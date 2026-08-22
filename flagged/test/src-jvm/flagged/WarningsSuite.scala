package flagged

import dotty.tools.dotc.Driver
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.interfaces
import dotty.tools.dotc.reporting.{Diagnostic, Reporter}

import java.nio.file.Files

/** Compiles small snippets with the dotty `Driver` against this suite's own classpath (the same
  * device as `bench.CompileBench`) and asserts the diagnostics `AnnotMirror` synthesis emits: the
  * warnings for annotation occurrences dropped from the mirror.
  */
class WarningsSuite extends munit.FunSuite:

  val nonConstant = "is not a compile-time constant (a literal, a constant-folded expression," +
    " or a tuple of constants)"

  /** All warnings emitted compiling `src`; compile errors fail the test. */
  def warningsOf(src: String): List[String] =
    val dir     = Files.createTempDirectory("flagged-warnings")
    val srcFile = dir.resolve("snippet.scala")
    Files.writeString(srcFile, src)
    val out      = Files.createDirectories(dir.resolve("out"))
    val errors   = List.newBuilder[String]
    val warnings = List.newBuilder[String]
    val reporter = new Reporter:
      def doReport(dia: Diagnostic)(using Context): Unit =
        if dia.level >= interfaces.Diagnostic.ERROR then errors += dia.message
        else if dia.level == interfaces.Diagnostic.WARNING then warnings += dia.message
    Driver().process(
      Array("-d", out.toString, "-classpath", sys.props("java.class.path"), srcFile.toString),
      reporter
    )
    assert(errors.result().isEmpty, s"snippet does not compile:\n${errors.result().mkString("\n")}")
    warnings.result()

  test("a dropped annotation warns, naming the annotation, its site, and the argument") {
    val warnings = warningsOf(
      """package snip
        |import flagged.*
        |object S1:
        |  val rt: String = "a".trim
        |  @opt(help = rt) case class NC(x: Int = 0)
        |  val am = meta.AnnotMirror.ofProduct[NC]
        |""".stripMargin
    )
    assertEquals(
      warnings,
      List(s"@opt on class snip.S1.NC is ignored: its `help` argument $nonConstant")
    )
  }

  test("several drops in one expansion aggregate into a single warning listing each site") {
    val warnings = warningsOf(
      """package snip
        |import flagged.*
        |object S2:
        |  val rt: String = "a".trim
        |  @cmd(help = rt) case class NC(@opt(help = rt) x: Int = 0)
        |  val am = meta.AnnotMirror.ofProduct[NC]
        |""".stripMargin
    )
    assertEquals(
      warnings,
      List(
        s"- @cmd on class snip.S2.NC is ignored: its `help` argument $nonConstant\n" +
          s"- @opt on parameter `x` of class snip.S2.NC is ignored: its `help` argument " +
          nonConstant
      )
    )
  }

  test("a curried annotation constructor warns as unmirrorable") {
    val warnings = warningsOf(
      """package snip
        |import flagged.*
        |final case class curry(a: String)(b: String) extends scala.annotation.StaticAnnotation
        |object S3:
        |  @curry("x")("y") case class NC(x: Int = 0)
        |  val am = meta.AnnotMirror.ofProduct[NC]
        |""".stripMargin
    )
    assertEquals(
      warnings,
      List(
        "@curry on class snip.S3.NC is ignored: annotations applied with several argument" +
          " lists cannot be rebuilt through Mirror.ProductOf"
      )
    )
  }

  test("@nowarn at the annotated definition suppresses the warning") {
    val warnings = warningsOf(
      """package snip
        |import flagged.*
        |object S4:
        |  val rt: String = "a".trim
        |  @scala.annotation.nowarn("msg=is ignored") @opt(help = rt)
        |  case class NC(x: Int = 0)
        |  val am = meta.AnnotMirror.ofProduct[NC]
        |""".stripMargin
    )
    assertEquals(warnings, Nil)
  }
