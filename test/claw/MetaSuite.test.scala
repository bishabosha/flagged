package claw

import claw.meta.{Ann, AnnotMirror}
import claw.internal.{Derive, FieldAnnots, TargetAnnots}
import scala.deriving.Mirror
import scala.annotation.targetName

// test annotation with default arguments, for the named-args/defaults tests
final case class tagged(label: String = "none", level: Int = 1)
    extends scala.annotation.StaticAnnotation derives meta.Defaults

class MetaSuite extends munit.FunSuite:

  test("find extracts a typed annotation from a mirrored slot at compile time") {
    type Slot =
      Ann[short, 'v' *: EmptyTuple, false *: EmptyTuple] *:
        Ann[help, "text" *: EmptyTuple, false *: EmptyTuple] *: EmptyTuple
    val s: Option[Char]   = AnnotMirror.find[short, Slot].map(_.value)
    val n: Option[String] = AnnotMirror.find[name, Slot].map(_.value)
    assertEquals(s, Some('v'))
    assertEquals(n, None)
  }

  test("Defaults is an index switch that throws on invalid indices") {
    case class Partial(a: Int, b: Int = 2, c: String = "x")
    val d = meta.Defaults.derived[Partial]
    assert(!d.hasDefault(0))
    assert(d.hasDefault(1) && d.hasDefault(2))
    assertEquals(d.defaultArgument(1), 2)
    assertEquals(d.defaultArgument(2), "x")
    intercept[NoSuchElementException](d.defaultArgument(0))
    intercept[NoSuchElementException](d.defaultArgument(9))
  }

  inline def fromMirror[A: Mirror.ProductOf](t: NamedTuple.From[A]): A =
    summon[Mirror.ProductOf[A]].fromProduct(t.asInstanceOf[Product])

  test("defaulted positions are looked up through the Defaults mirror") {
    // hand-written mirror type: label defaulted (index 0), level provided
    type Slot = Ann[tagged, (0, 7), (true, false)] *: EmptyTuple
    assertEquals(AnnotMirror.find[tagged, Slot].map(fromMirror[tagged]), Some(tagged("none", 7)))
  }

  test("annotation mirror resolves named arguments and fills constant defaults") {
    @tagged(level = 3) case class ByName(x: Int = 0)
    val am = AnnotMirror.ofProduct[ByName]
    assertEquals(
      AnnotMirror.find[tagged, am.MirroredSelfAnnotations].map(fromMirror[tagged]),
      Some(tagged("none", 3))
    )

    @tagged(level = 5, label = "hi") case class Permuted(x: Int = 0)
    val am2 = AnnotMirror.ofProduct[Permuted]
    assertEquals(
      AnnotMirror.find[tagged, am2.MirroredSelfAnnotations].map(fromMirror[tagged]),
      Some(tagged("hi", 5))
    )

    @tagged() case class AllDefaults(x: Int = 0)
    val am3 = AnnotMirror.ofProduct[AllDefaults]
    assertEquals(
      AnnotMirror.find[tagged, am3.MirroredSelfAnnotations].map(fromMirror[tagged]),
      Some(tagged("none", 1))
    )
  }

  test("annotation extraction for a case class is fully typed") {
    @help("a greeter")
    case class G(@short('n') @help("who") name: String = "world", quiet: Boolean = false)

    val a = Derive.productAnnots[G]
    assertEquals(a.onType, TargetAnnots(None, Some("a greeter")))
    assertEquals(
      a.perField,
      List(
        FieldAnnots(None, Some('n'), Some("who"), positional = false),
        FieldAnnots.empty
      )
    )
  }

  test("annotation extraction for an enum captures per-case annotations") {
    val a = Derive.sumAnnots[Git]
    assertEquals(a.onType, TargetAnnots(None, Some("A tiny git-like tool")))
    assertEquals(
      a.perCase.map(_.help),
      List(
        Some("Clone a repository"),
        Some("Manage remotes"),
        Some("Show status")
      )
    )
  }

  test("non-constant and non-case-class annotations are not mirrored") {
    // @targetName is not a case class; nothing claw-relevant should surface
    @targetName("Foo") case class Old(@short('x') a: Int = 0)
    val ann = summon[AnnotMirror.Product[Old]]
    summon[ann.MirroredSelfAnnotations =:= EmptyTuple] // no @targetName in the self slot

    val oldAnnots = Derive.productAnnots[Old]
    assertEquals(oldAnnots.onType, TargetAnnots.empty)
    assertEquals(
      oldAnnots.perField,
      List(FieldAnnots(None, Some('x'), None, positional = false))
    )
  }
