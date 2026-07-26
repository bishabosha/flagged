package flagged

// ---- Parser.Product: fixed multi-token values ----------------------------------

case class Pt(x: Int, y: Int) derives Parser.Product

case class Plot(
    @short('p') point: (Int, Int) = (0, 0),
    corner: Option[(Int, Int)] = None,
    label: String = ""
) derives Parser.Command

case class Move(origin: Pt = Pt(0, 0), target: Pt) derives Parser.Command

case class PosProduct(
    @positional at: (Int, Int),
    @positional rest: List[String] = Nil
) derives Parser.Command

case class MixedProduct(span: (Int, String, Double)) derives Parser.Command

// ---- @split --------------------------------------------------------------------

case class SplitOpts(
    @short('n') @split nums: List[Int] = Nil,
    @split(';') tags: Set[String] = Set.empty,
    @split defines: Map[String, Int] = Map.empty
) derives Parser.Command

case class SplitPositional(@positional @split nums: List[Int] = Nil) derives Parser.Command

// ---- @greedy -------------------------------------------------------------------

case class GreedyOpts(
    @short('n') @greedy nums: List[Int] = Nil,
    @short('w') @greedy words: Vector[String] = Vector.empty,
    @short('v') verbose: Boolean = false
) derives Parser.Command

case class GreedyTrailing(
    @greedy nums: List[Int] = Nil,
    cmd: Trailing = Trailing()
) derives Parser.Command

class MultiValueSuite extends munit.FunSuite:

  def ok[A](r: ParseResult[A]): A = r match
    case Result.Ok(a)                         => a
    case Result.Err(ParseError.Help(t))       => fail(s"expected success, got help:\n$t")
    case Result.Err(ParseError.Failure(m, _)) => fail(s"expected success, got failure: $m")

  def err[A](r: ParseResult[A]): String = r match
    case Result.Err(ParseError.Failure(m, _)) => m
    case other                                => fail(s"expected failure, got $other")

  // ---- products ----------------------------------------------------------------

  test("tuple product consumes consecutive tokens") {
    assertEquals(
      ok(Flagged.parse[Plot](Seq("--point", "3", "4"))),
      Plot(point = (3, 4))
    )
  }

  test("product short option and following options parse normally") {
    assertEquals(
      ok(Flagged.parse[Plot](Seq("-p", "3", "4", "--label", "hi"))),
      Plot(point = (3, 4), label = "hi")
    )
  }

  test("Option product: absent is None, present is Some") {
    assertEquals(ok(Flagged.parse[Plot](Nil)).corner, None)
    assertEquals(
      ok(Flagged.parse[Plot](Seq("--corner", "7", "8"))).corner,
      Some((7, 8))
    )
  }

  test("repeated product occurrence: last wins") {
    assertEquals(
      ok(Flagged.parse[Plot](Seq("--point", "1", "2", "--point", "3", "4"))),
      Plot(point = (3, 4))
    )
  }

  test("product with mixed element types") {
    assertEquals(
      ok(Flagged.parse[MixedProduct](Seq("--span", "1", "two", "3.5"))),
      MixedProduct((1, "two", 3.5))
    )
  }

  test("derived case-class product, defaults and required") {
    assertEquals(
      ok(Flagged.parse[Move](Seq("--target", "5", "6"))),
      Move(target = Pt(5, 6))
    )
    assertEquals(
      ok(Flagged.parse[Move](Seq("--origin", "1", "2", "--target", "5", "6"))),
      Move(Pt(1, 2), Pt(5, 6))
    )
    assert(err(Flagged.parse[Move](Nil)).contains("--target"))
  }

  test("product rejects the = form") {
    assert(err(Flagged.parse[Plot](Seq("--point=3"))).contains("2 values"))
  }

  test("product with missing tokens reports") {
    assert(err(Flagged.parse[Plot](Seq("--point", "3"))).contains("requires a value"))
  }

  test("product element parse error reports the option") {
    val e = err(Flagged.parse[Plot](Seq("--point", "3", "x")))
    assert(e.contains("--point"), e)
    assert(e.contains("'x'"), e)
  }

  test("product does not consume option-like tokens") {
    val e = err(Flagged.parse[Plot](Seq("--point", "3", "--label", "hi")))
    assert(e.contains("requires a value"), e)
  }

  test("positional product") {
    assertEquals(
      ok(Flagged.parse[PosProduct](Seq("3", "4", "rest1", "rest2"))),
      PosProduct((3, 4), List("rest1", "rest2"))
    )
  }

  test("product help shows one metavar per element") {
    val h = Flagged.help[Move]
    assert(h.contains("--origin <x> <y>"), h)
    val hp = Flagged.help[Plot]
    assert(hp.contains("--point <int> <int>"), hp)
  }

  // ---- @split ------------------------------------------------------------------

  test("split divides one value into elements") {
    assertEquals(
      ok(Flagged.parse[SplitOpts](Seq("--nums", "1,2,3"))),
      SplitOpts(nums = List(1, 2, 3))
    )
  }

  test("split occurrences accumulate") {
    assertEquals(
      ok(Flagged.parse[SplitOpts](Seq("--nums", "1,2", "-n", "3", "--nums=4,5"))),
      SplitOpts(nums = List(1, 2, 3, 4, 5))
    )
  }

  test("split with a custom separator") {
    assertEquals(
      ok(Flagged.parse[SplitOpts](Seq("--tags", "a;b;c"))),
      SplitOpts(tags = Set("a", "b", "c"))
    )
  }

  test("split map entries") {
    assertEquals(
      ok(Flagged.parse[SplitOpts](Seq("--defines", "a=1,b=2"))),
      SplitOpts(defines = Map("a" -> 1, "b" -> 2))
    )
  }

  test("split segment errors are reported") {
    val e = err(Flagged.parse[SplitOpts](Seq("--nums", "1,x,3")))
    assert(e.contains("'x'"), e)
  }

  test("split on a positional repeated field") {
    assertEquals(
      ok(Flagged.parse[SplitPositional](Seq("1,2", "3"))),
      SplitPositional(List(1, 2, 3))
    )
  }

  // ---- @greedy -----------------------------------------------------------------

  test("greedy consumes following free tokens") {
    assertEquals(
      ok(Flagged.parse[GreedyOpts](Seq("--nums", "10", "20", "99", "7"))),
      GreedyOpts(nums = List(10, 20, 99, 7))
    )
  }

  test("greedy stops at the next option") {
    assertEquals(
      ok(Flagged.parse[GreedyOpts](Seq("--nums", "1", "2", "--verbose"))),
      GreedyOpts(nums = List(1, 2), verbose = true)
    )
  }

  test("greedy occurrences interleave and accumulate") {
    assertEquals(
      ok(Flagged.parse[GreedyOpts](Seq("-n", "1", "2", "-v", "-n", "3"))),
      GreedyOpts(nums = List(1, 2, 3), verbose = true)
    )
  }

  test("greedy consumes negative numbers") {
    assertEquals(
      ok(Flagged.parse[GreedyOpts](Seq("--nums", "10", "-5", "3"))),
      GreedyOpts(nums = List(10, -5, 3))
    )
  }

  test("greedy attached and = forms pin one element") {
    assertEquals(
      ok(Flagged.parse[GreedyOpts](Seq("--nums=1", "-v"))),
      GreedyOpts(nums = List(1), verbose = true)
    )
    val e = err(Flagged.parse[GreedyOpts](Seq("--nums=1", "2")))
    assert(e.contains("unexpected argument '2'"), e)
  }

  test("two greedy options") {
    assertEquals(
      ok(Flagged.parse[GreedyOpts](Seq("-n", "1", "2", "-w", "a", "b"))),
      GreedyOpts(nums = List(1, 2), words = Vector("a", "b"))
    )
  }

  test("greedy stops at -- and trailing collects the rest") {
    assertEquals(
      ok(Flagged.parse[GreedyTrailing](Seq("--nums", "1", "2", "--", "cmd", "--flag"))),
      GreedyTrailing(List(1, 2), Trailing(Vector("cmd", "--flag")))
    )
  }

  // ---- compile-time rules ------------------------------------------------------

  test("@split requires a repeated field") {
    val e = compileErrors("case class C(@split x: Int = 0) derives Parser.Command")
    assert(e.contains("@split requires a field with a repeated Parser"), e)
  }

  test("@greedy requires a repeated field") {
    val e = compileErrors("case class C(@greedy x: String = \"\") derives Parser.Command")
    assert(e.contains("@greedy requires a field with a repeated Parser"), e)
  }

  test("@split cannot combine with @greedy") {
    val e = compileErrors("case class C(@split @greedy x: List[Int] = Nil) derives Parser.Command")
    assert(e.contains("@split cannot be combined with @greedy"), e)
  }

  test("@greedy on a positional is rejected") {
    val e = compileErrors(
      "case class C(@positional @greedy x: List[Int] = Nil) derives Parser.Command"
    )
    assert(e.contains("a repeated positional is already greedy"), e)
  }

  test("@greedy cannot coexist with positional fields") {
    val e = compileErrors(
      "case class C(@greedy x: List[Int] = Nil, @positional y: String) derives Parser.Command"
    )
    assert(e.contains("cannot have positional fields"), e)
  }

  test("@greedy cannot coexist with a subcommand field") {
    val e = compileErrors(
      "case class C(@greedy x: List[Int] = Nil, action: SimpleCmd) derives Parser.Command"
    )
    assert(e.contains("cannot have a subcommand field"), e)
  }

  test("Option of a product is fine, product in a collection is not") {
    val e = compileErrors("case class C(pts: List[(Int, Int)] = Nil) derives Parser.Command")
    assert(e.nonEmpty, "expected a missing-instance error")
  }
