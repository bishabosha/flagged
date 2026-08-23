package flagged

import flagged.meta.{ArgumentList, AnnotMirror}
import flagged.internal.{Derive, FieldAnnots, MaybeChar, TargetAnnots}
import scala.deriving.Mirror
import scala.annotation.targetName
import flagged.internal.Annots

// test annotation with default arguments, for the named-args/defaults tests
final case class tagged(label: String = "none", level: Int = 1)
    extends scala.annotation.StaticAnnotation derives meta.Defaults

// referenced from annotation arguments below: a constant-typed `final val` is a constant
final val TestSep = "."

// as is an `inline val`
inline val TestPrefix = "pre"

// type-parameterised test annotation: mirrors at its applied type
final case class poly[T](default: T, note: String = "") extends scala.annotation.StaticAnnotation

class MetaSuite extends munit.FunSuite:

  // the derivation extracts one slot at a time, as the field walk reaches each field; these
  // collect the same per-field records for a whole class so they can be asserted together
  inline def selfAnnotsOf[A]: TargetAnnots =
    scala.compiletime.summonFrom:
      case am: AnnotMirror.Product[A] => Annots.targetAnnotsOf[am.MirroredSelfAnnotations]

  inline def fieldAnnotsOf[A]: IndexedSeq[FieldAnnots] =
    scala.compiletime.summonFrom:
      case am: AnnotMirror.Product[A] => eachSlot[am.MirroredAnnotations]

  inline def eachSlot[Slots]: IndexedSeq[FieldAnnots] =
    inline scala.compiletime.erasedValue[Slots] match
      case _: EmptyTuple => Vector.empty
      // extraction is shape-blind: the positional-by-default rule is applied by the derivation,
      // which knows the field's parser shape — here the named-option default reads the raw record
      case _: (h *: t) => Annots.fieldAnnotsOf[h](false) +: eachSlot[t]

  test("find extracts a typed annotation from a sparse mirrored slot at compile time") {
    // hand-written mirror type for `@opt(help = "text", short = 'v')`: only the provided
    // argument columns are encoded — help is opt's parameter 1, short parameter 2
    type Slot = ArgumentList[opt, ("help", "short"), ("text", 'v'), (1, 2)] *: EmptyTuple
    val o = AnnotMirror.find[opt, Slot]
    assertEquals(o.map(_.short), Some('v'))
    assertEquals(o.map(_.help), Some("text"))
    assertEquals(o.map(_.name), Some("")) // omitted: filled in from the default
    assertEquals(AnnotMirror.find[version, Slot].map(_.value), None)
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

  test("omitted positions are looked up through the Defaults mirror") {
    // hand-written sparse mirror type: label omitted, level provided at parameter index 1
    type Slot = ArgumentList[tagged, "level" *: EmptyTuple, 7 *: EmptyTuple, 1 *: EmptyTuple] *:
      EmptyTuple
    assertEquals(AnnotMirror.findExact[tagged, Slot], Some(tagged("none", 7)))
    assertEquals(AnnotMirror.find[tagged, Slot].map(fromMirror[tagged]), Some(tagged("none", 7)))
  }

  test("annotation mirror resolves named arguments and fills constant defaults") {
    @tagged(level = 3) case class ByName(x: Int = 0)
    val am = AnnotMirror.ofProduct[ByName]
    assertEquals(
      AnnotMirror.findExact[tagged, am.MirroredSelfAnnotations],
      Some(tagged("none", 3))
    )
    assertEquals(
      AnnotMirror.find[tagged, am.MirroredSelfAnnotations].map(fromMirror[tagged]),
      Some(tagged("none", 3))
    )

    @tagged(level = 5, label = "hi") case class Permuted(x: Int = 0)
    val am2 = AnnotMirror.ofProduct[Permuted]
    assertEquals(
      AnnotMirror.findExact[tagged, am2.MirroredSelfAnnotations],
      Some(tagged("hi", 5))
    )
    assertEquals(
      AnnotMirror.find[tagged, am2.MirroredSelfAnnotations].map(fromMirror[tagged]),
      Some(tagged("hi", 5))
    )

    @tagged() case class AllDefaults(x: Int = 0)
    val am3 = AnnotMirror.ofProduct[AllDefaults]
    assertEquals(
      AnnotMirror.findExact[tagged, am3.MirroredSelfAnnotations],
      Some(tagged("none", 1))
    )
    assertEquals(
      AnnotMirror.find[tagged, am3.MirroredSelfAnnotations].map(fromMirror[tagged]),
      Some(tagged("none", 1))
    )
  }

  test("annotation extraction for a case class is fully typed") {
    @cmd(help = "a greeter")
    case class G(@opt(help = "who", short = 'n') name: String = "world", quiet: Boolean = false)

    assertEquals(selfAnnotsOf[G], TargetAnnots(None, Some("a greeter")))
    assertEquals(
      fieldAnnotsOf[G],
      Vector(
        FieldAnnots(None, MaybeChar('n'), Some("who"), positional = false),
        FieldAnnots.empty
      )
    )
  }

  test("aliases arguments mirror as constant tuples and materialise back") {
    @cmd(name = "prog", aliases = ("p", "pr"))
    case class A(@opt(name = "ex", aliases = "extra" *: EmptyTuple) x: Int = 0)

    assertEquals(
      selfAnnotsOf[A],
      TargetAnnots(Some("prog"), None, false, aliases = Vector("p", "pr"))
    )
    assertEquals(
      fieldAnnotsOf[A],
      Vector(
        FieldAnnots(
          Some("ex"),
          MaybeChar.empty,
          None,
          positional = false,
          aliases = Vector("extra")
        )
      )
    )
    // materialisation through find rebuilds the tuple value (and defaults for the rest)
    val am = AnnotMirror.ofProduct[A]
    assertEquals(
      AnnotMirror.find[cmd, am.MirroredSelfAnnotations].map(_.aliases),
      Some(("p", "pr"))
    )
  }

  test("an unannotated slot reads back as all-defaults") {
    case class P(a: Int = 0, b: String = "")
    assertEquals(fieldAnnotsOf[P], Vector(FieldAnnots.empty, FieldAnnots.empty))
  }

  test("bare @opt and @cmd slots share the empty records, like unannotated slots") {
    @cmd case class B(@opt a: Int = 0, b: String = "")
    assert(selfAnnotsOf[B] eq TargetAnnots.empty)
    val recs = fieldAnnotsOf[B]
    assert(recs(0) eq FieldAnnots.empty) // bare @opt: no constructor call
    assert(recs(1) eq FieldAnnots.empty) // no annotation, under the helper's named default
  }

  test("all-unannotated sum cases share Vector.empty, read back as all-defaults") {
    enum Plain:
      case A, B
    val a = Annots.sumAnnots[Plain]
    assert(a.perCase.isEmpty)
    assertEquals(a.caseAnnots(1), TargetAnnots.empty)
  }

  test("annotation extraction for an enum captures per-case annotations") {
    val a = Annots.sumAnnots[Git]
    assertEquals(a.onType, TargetAnnots(None, Some("A tiny git-like tool")))
    assertEquals(
      a.perCase.map(_.help),
      Vector(
        Some("Clone a repository"),
        Some("Manage remotes"),
        Some("Show status")
      )
    )
  }

  test("non-constant and non-case-class annotations are not mirrored") {
    // @targetName is not a case class; nothing flagged-relevant should surface
    @targetName("Foo") case class Old(@opt(short = 'x') a: Int = 0)
    val ann = summon[AnnotMirror.Product[Old]]
    summon[ann.MirroredSelfAnnotations =:= EmptyTuple] // no @targetName in the self slot

    assertEquals(selfAnnotsOf[Old], TargetAnnots.empty)
    assertEquals(
      fieldAnnotsOf[Old],
      Vector(FieldAnnots(None, MaybeChar('x'), None, positional = false))
    )

    // a runtime value can never be encoded as a singleton type: the occurrences are left out of
    // the mirror — and, unlike the shapes above, with a compile-time warning (suppressed here)
    // that lists every dropped occurrence and where it sits
    val rt = List("a", "b").mkString
    @scala.annotation.nowarn("msg=is ignored") @tagged(label = rt)
    case class NonConst(@opt(help = rt) x: Int = 0)
    val ann2 = summon[AnnotMirror.Product[NonConst]]
    summon[ann2.MirroredSelfAnnotations =:= EmptyTuple]
    summon[ann2.MirroredAnnotations =:= (EmptyTuple *: EmptyTuple)]
  }

  test("constant-folded expressions and final-val references mirror as constants") {
    // the typer folds pure operations on constants: the argument's *type* is the folded
    // constant even though its tree is an application
    @tagged(label = "con" + "cat") case class Folded(x: Int = 0)
    val am = AnnotMirror.ofProduct[Folded]
    summon[
      am.MirroredSelfAnnotations =:=
        (ArgumentList[tagged, "label" *: EmptyTuple, "concat" *: EmptyTuple, 0 *: EmptyTuple] *:
          EmptyTuple)
    ]
    assertEquals(
      AnnotMirror.findExact[tagged, am.MirroredSelfAnnotations],
      Some(tagged("concat", 1))
    )

    // folding through a constant `final val` reference, and over non-string primitives
    @tagged(label = "v" + TestSep + "1", level = 2 + 3) case class Mixed(x: Int = 0)
    val am2 = AnnotMirror.ofProduct[Mixed]
    assertEquals(
      AnnotMirror.findExact[tagged, am2.MirroredSelfAnnotations],
      Some(tagged("v.1", 5))
    )

    // a bare reference to a constant-typed `final val`
    @tagged(label = TestSep) case class Ref(x: Int = 0)
    val am3 = AnnotMirror.ofProduct[Ref]
    assertEquals(
      AnnotMirror.findExact[tagged, am3.MirroredSelfAnnotations],
      Some(tagged(".", 1))
    )

    // folding applies element-wise inside tuple arguments too: a TupleN literal mixing a
    // concat, a fold over a `final val`, and an `inline val` reference...
    @cmd(aliases = ("ali" + "as", TestSep + "a", TestPrefix)) case class Tup(x: Int = 0)
    val am4 = AnnotMirror.ofProduct[Tup]
    assertEquals(
      AnnotMirror.find[cmd, am4.MirroredSelfAnnotations].map(_.aliases),
      Some(("alias", ".a", "pre"))
    )
    // ...and the `*:` cons form with a folded head, encoded as the folded constant type
    @cmd(aliases = ("con" + "s") *: EmptyTuple) case class Cons(x: Int = 0)
    val am4b = AnnotMirror.ofProduct[Cons]
    summon[
      am4b.MirroredSelfAnnotations =:=
        (ArgumentList[
          cmd,
          "aliases" *: EmptyTuple,
          ("cons" *: EmptyTuple) *: EmptyTuple,
          4 *: EmptyTuple
        ] *: EmptyTuple)
    ]
    assertEquals(
      AnnotMirror.find[cmd, am4b.MirroredSelfAnnotations].map(_.aliases),
      Some("cons" *: EmptyTuple)
    )

    // an `inline val` reference is a guaranteed constant, alone or in a fold
    @tagged(label = TestPrefix) case class Inl(x: Int = 0)
    val am5 = AnnotMirror.ofProduct[Inl]
    assertEquals(
      AnnotMirror.findExact[tagged, am5.MirroredSelfAnnotations],
      Some(tagged("pre", 1))
    )
    @tagged(label = TestPrefix + TestSep + "1") case class InlFold(x: Int = 0)
    val am6 = AnnotMirror.ofProduct[InlFold]
    assertEquals(
      AnnotMirror.findExact[tagged, am6.MirroredSelfAnnotations],
      Some(tagged("pre.1", 1))
    )
  }

  test("type-parameterised annotations mirror at their applied type") {
    @poly(default = 3, note = "n" + "b") case class WithPoly(x: Int = 0)
    val am = AnnotMirror.ofProduct[WithPoly]
    summon[
      am.MirroredSelfAnnotations =:=
        (ArgumentList[poly[Int], ("default", "note"), (3, "nb"), (0, 1)] *: EmptyTuple)
    ]
    given di: meta.Defaults[poly[Int]] = meta.Defaults.derived
    assertEquals(
      AnnotMirror.findExact[poly[Int], am.MirroredSelfAnnotations],
      Some(poly(3, "nb"))
    )
    // the omitted `note` materialises from the Defaults mirror at the applied type
    @poly(default = 'c') case class WithChar(x: Int = 0)
    val am2                             = AnnotMirror.ofProduct[WithChar]
    given dc: meta.Defaults[poly[Char]] = meta.Defaults.derived
    assertEquals(
      AnnotMirror.findExact[poly[Char], am2.MirroredSelfAnnotations],
      Some(poly('c', ""))
    )
  }
