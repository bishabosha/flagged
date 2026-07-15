package claw

import claw.internal.{Ann, AnnotMirror, Derive}

class MetaSuite extends munit.FunSuite:

  test("materialize rebuilds annotation values from their mirrored types") {
    val anns = AnnotMirror.materialize[Ann[short, 'v' *: EmptyTuple] *: Ann[help, "text" *: EmptyTuple] *: EmptyTuple]
    assertEquals(anns, List(short('v'), help("text")))
  }

  test("annotation mirror of a case class captures type- and field-level annotations") {
    @help("a greeter")
    case class G(@short('n') @help("who") name: String = "world", quiet: Boolean = false)

    val a = Derive.productAnnots[G]
    assertEquals(a.onType, List[Any](help("a greeter")))
    assertEquals(a.perField, List(List[Any](short('n'), help("who")), Nil))
    assertEquals(a.perCase, Nil)
  }

  test("annotation mirror of an enum captures per-case annotations") {
    val a = Derive.sumAnnots[Git]
    assertEquals(a.onType, List[Any](help("A tiny git-like tool")))
    assertEquals(a.perCase.map(_.headOption), List(
      Some(help("Clone a repository")),
      Some(help("Manage remotes")),
      Some(help("Show status"))
    ))
  }

  test("non-constant and curried annotations are not mirrored") {
    // @deprecated is not a case class; nothing claw-relevant should surface
    @deprecated("gone", "0.1") case class Old(@short('x') a: Int = 0)
    val ann = Derive.productAnnots[Old]
    assertEquals(ann.onType, Nil)
    assertEquals(ann.perField, List(List[Any](short('x'))))
  }
