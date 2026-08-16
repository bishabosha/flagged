package flagged

import scala.deriving.Mirror

object varargConcat:
  @cmd def concat(prefix: String, items: String*): String = (prefix +: items).mkString("+")

object varargScan:
  @cmd def scan(ports: Port*): String = ports.map(_.value).mkString(",")

object varargTags:
  @cmd def tag(@opt(short = 't') tags: String*): String = tags.mkString(",")

/** Vararg parameters mirror as `Seq` — the normalization `Mirror` synthesis applies to a vararg
  * case-class field, matched by [[meta.MethodMirror]] for `@cmd` methods — so both derivations give
  * them the repeated shape, and zero occurrences parse to empty.
  */
class VarargSuite extends munit.FunSuite:

  def ok[A](r: ParseResult[A]): A = r match
    case Result.Ok(a) => a
    case other        => fail(s"expected success, got $other")

  test("Mirror encodes a vararg case-class field as Seq") {
    case class Concat(prefix: String, items: String*)
    summon[Mirror.ProductOf[Concat] { type MirroredElemTypes = (String, Seq[String]) }]
  }

  test("a vararg case class derives a repeated positional") {
    case class Concat(prefix: String, items: String*) derives Parser.Command
    assertEquals(ok(Flagged.parse[Concat](Seq("p", "a", "b"))), Concat("p", "a", "b"))
    assertEquals(ok(Flagged.parse[Concat](Seq("p"))), Concat("p"))
  }

  test("a vararg @cmd method parameter is a repeated positional") {
    assertEquals(ok(Flagged.parse[varargConcat.type](Seq("p", "a", "b"))), "p+a+b")
    assertEquals(ok(Flagged.parse[varargConcat.type](Seq("p"))), "p")
  }

  test("a vararg of a FromString type parses each occurrence with the bridge") {
    assertEquals(ok(Flagged.parse[varargScan.type](Seq("80", "443"))), "80,443")
  }

  test("@opt on a vararg parameter makes it a repeated named option") {
    assertEquals(ok(Flagged.parse[varargTags.type](Seq("-t", "a", "--tags", "b"))), "a,b")
    assertEquals(ok(Flagged.parse[varargTags.type](Nil)), "")
  }
