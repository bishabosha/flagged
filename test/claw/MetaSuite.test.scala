package claw

import claw.internal.{Ann, AnnotMirror, Derive, FieldAnnots, TargetAnnots}

class MetaSuite extends munit.FunSuite:

  test("find extracts a typed annotation from a mirrored slot at compile time") {
    type Slot = Ann[short, 'v' *: EmptyTuple] *: Ann[help, "text" *: EmptyTuple] *: EmptyTuple
    val s: Option[short] = AnnotMirror.find[short, Slot]
    val n: Option[name] = AnnotMirror.find[name, Slot]
    assertEquals(s, Some(short('v')))
    assertEquals(n, None)
  }

  test("annotation extraction for a case class is fully typed") {
    @help("a greeter")
    case class G(@short('n') @help("who") name: String = "world", quiet: Boolean = false)

    val a = Derive.productAnnots[G]
    assertEquals(a.onType, TargetAnnots(None, Some(help("a greeter"))))
    assertEquals(
      a.perField,
      List(
        FieldAnnots(None, Some(short('n')), Some(help("who")), positional = false),
        FieldAnnots.empty
      )
    )
  }

  test("annotation extraction for an enum captures per-case annotations") {
    val a = Derive.sumAnnots[Git]
    assertEquals(a.onType, TargetAnnots(None, Some(help("A tiny git-like tool"))))
    assertEquals(a.perCase.map(_.help), List(
      Some(help("Clone a repository")),
      Some(help("Manage remotes")),
      Some(help("Show status"))
    ))
  }

  test("non-constant and non-case-class annotations are not mirrored") {
    // @deprecated is not a case class; nothing claw-relevant should surface
    @deprecated("gone", "0.1") case class Old(@short('x') a: Int = 0)
    val ann = Derive.productAnnots[Old]
    assertEquals(ann.onType, TargetAnnots.empty)
    assertEquals(ann.perField, List(FieldAnnots(None, Some(short('x')), None, positional = false)))
  }
