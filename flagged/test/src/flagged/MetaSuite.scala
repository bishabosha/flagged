package flagged

import flagged.meta.{ArgumentList, AnnotMirror}
import flagged.internal.Derive
import scala.deriving.Mirror
import scala.annotation.targetName
import flagged.internal.Annots

// test annotation with default arguments, for the named-args/defaults tests
final case class tagged(label: String = "none", level: Int = 1)
    extends scala.annotation.StaticAnnotation derives meta.Defaults

class MetaSuite extends munit.FunSuite:

  // the derivation extracts one slot at a time, as the field walk reaches each field; these
  // collect the same per-field records for a whole class so they can be asserted together
  inline def selfAnnotsOf[A]: cmd =
    scala.compiletime.summonFrom:
      case am: AnnotMirror.Product[A] => Annots.targetAnnotsOf[am.MirroredSelfAnnotations]

  inline def fieldAnnotsOf[A]: IndexedSeq[opt] =
    scala.compiletime.summonFrom:
      case am: AnnotMirror.Product[A] => eachSlot[am.MirroredAnnotations]

  inline def eachSlot[Slots]: IndexedSeq[opt] =
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

    assertEquals(selfAnnotsOf[G], cmd(help = "a greeter"))
    assertEquals(
      fieldAnnotsOf[G],
      Vector(
        opt(help = "who", short = 'n'),
        opt()
      )
    )
  }

  test("aliases arguments mirror as constant tuples and materialise back") {
    @cmd(name = "prog", aliases = ("p", "pr"))
    case class A(@opt(name = "ex", aliases = "extra" *: EmptyTuple) x: Int = 0)

    assertEquals(
      selfAnnotsOf[A],
      cmd(name = "prog", aliases = ("p", "pr"))
    )
    assertEquals(
      fieldAnnotsOf[A],
      Vector(opt(name = "ex", aliases = "extra" *: EmptyTuple))
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
    assertEquals(fieldAnnotsOf[P], Vector(opt(), opt()))
  }

  test("bare @opt and @cmd slots share the empty records, like unannotated slots") {
    @cmd case class B(@opt a: Int = 0, b: String = "")
    assert(selfAnnotsOf[B] eq Annots.bareCmd)
    val recs = fieldAnnotsOf[B]
    assert(recs(0) eq Annots.bareOpt) // bare @opt: no constructor call
    assert(recs(1) eq Annots.bareOpt) // no annotation, under the helper's named default
  }

  test("all-unannotated sum cases share Vector.empty, read back as all-defaults") {
    enum Plain:
      case A, B
    val a = Annots.sumAnnots[Plain]
    assert(a.perCase.isEmpty)
    assertEquals(a.caseAnnots(1), cmd())
  }

  test("annotation extraction for an enum captures per-case annotations") {
    val a = Annots.sumAnnots[Git]
    assertEquals(a.onType, cmd(help = "A tiny git-like tool"))
    assertEquals(
      a.perCase.map(_.help),
      Vector(
        "Clone a repository",
        "Manage remotes",
        "Show status"
      )
    )
  }

  test("non-constant and non-case-class annotations are not mirrored") {
    // @targetName is not a case class; nothing flagged-relevant should surface
    @targetName("Foo") case class Old(@opt(short = 'x') a: Int = 0)
    val ann = summon[AnnotMirror.Product[Old]]
    summon[ann.MirroredSelfAnnotations =:= EmptyTuple] // no @targetName in the self slot

    assertEquals(selfAnnotsOf[Old], cmd())
    assertEquals(
      fieldAnnotsOf[Old],
      Vector(opt(short = 'x'))
    )
  }
