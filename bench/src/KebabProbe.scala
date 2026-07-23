package bench

import java.nio.file.Files
import dotty.tools.dotc.Driver
import dotty.tools.dotc.reporting.StoreReporter
import flagged.internal.Assemble

/** Experiment: can the kebab-case transform run at compile time using only `scala.compiletime.ops`
  * types, and what does it cost?
  *
  * Generates self-contained sources defining `Kebab[S <: String]` — a per-character match-type walk
  * (`Substring`/`Length`/int arithmetic; a 26-arm table for lowercasing, since ops.string has no
  * case conversion) with two classifier variants: `regex` (`Matches`) and `table` (pure match
  * types) — and compiles them with a warm in-process driver, best of 5.
  *
  * Correctness is by construction: the `verify` sources embed `Assemble.kebab`'s output as one
  * static assertion per label, so a divergence from the runtime transform fails compilation; the
  * `dup` sources contain two identifiers that collide only after kebab-casing and must fail. The
  * `dedup` sources run the real use case — fold the labels, accumulate each kebab, check
  * pre-existence — in two encodings: a union type with `<:<`-summoned membership, and a tuple
  * accumulator with `ops.any.==` membership (the library's `DupNameErrAcc` style).
  *
  * Findings (Scala 3.8.3, M3 Max):
  *   - The transform works: both classifiers reduce identically to `Assemble.kebab` on all 50
  *     labels, at ~0.4 ms per identifier once the definitions are in scope (~64 ms all-in for the
  *     working dedup over 50, against a ~39 ms baseline and ~45 ms for the definitions alone).
  *     Classifier choice (regex vs tables) makes no measurable difference.
  *   - Ops only fold arguments that are plain constant types: an inline-match binder's `h & String`
  *     intersection leaves `Kebab[...]` stuck — loudly under `constValue` (E182), *silently* inside
  *     `summonFrom`. `ops.any.ToString[h]` normalizes it.
  *   - The union + `<:<` encoding never detects the duplicate, even over a union of fully folded
  *     constants: the evidence search does not establish the singleton-in-union subtyping here, and
  *     its failure mode is a silently passing check. The tuple + `==` match-type fold both works
  *     and fails loudly when stuck — it is the reliable encoding.
  *
  * ./mill bench.runMain bench.KebabProbe
  */
object KebabProbe:

  /** 50 typical multiword identifiers, including acronym and digit runs. */
  val labels: List[String] = List(
    "maxRetries", "logLevel", "dryRun", "outputFile", "inputPath", "connectTimeout",
    "readTimeoutMs", "httpProxy", "noColor", "followRedirects", "maxDepth", "userAgent", "cacheDir",
    "configFile", "logFormat", "quietMode", "verboseLevel", "retryDelayMs", "bufferSize",
    "chunkSizeKb", "tlsVersion", "certFile", "keyStorePath", "ipv4Only", "ipv6Prefix", "sha256Sum",
    "base64Encode", "utf8Output", "maxLineWidth", "tabWidth", "showLineNumbers", "ignoreCase",
    "wordRegexp", "includeGlob", "excludeDirs", "followSymlinks", "hiddenFiles", "colorScheme",
    "pagerCommand", "editorPath", "gitDir", "workTree", "remoteName", "branchPrefix", "upstreamUrl",
    "pushDefault", "mergeStrategy", "rebaseOnto", "parseURL", "enableHTTP2"
  )

  private val lowerArms =
    ('A' to 'Z').map(c => s"""  case "$c" => "${c.toLower}"""").mkString("\n")
  private val upperArms =
    ('a' to 'z').map(c => s"""  case "$c" => "${c.toUpper}"""").mkString("\n")
  private val digitArms =
    ('0' to '9').map(c => s"""  case "$c" => true""").mkString("\n")

  private val classifierRegex =
    """type IsUp[C <: String] = Matches[C, "[A-Z]"]
      |type IsDig[C <: String] = Matches[C, "[0-9]"]
      |type IsLet[C <: String] = Matches[C, "[A-Za-z]"]
      |""".stripMargin

  private val classifierTable =
    s"""type UpperOf[C <: String] <: String = C match
       |$upperArms
       |  case _ => C
       |type IsUp[C <: String] = NotB[LowerOf[C] == C]
       |type IsLow[C <: String] = NotB[UpperOf[C] == C]
       |type IsDig[C <: String] <: Boolean = C match
       |$digitArms
       |  case _ => false
       |type IsLet[C <: String] = IsUp[C] || IsLow[C]
       |""".stripMargin

  /** The transform plus the fold; everything from `scala.compiletime.ops` and match types. */
  private def machinery(classifier: String): String =
    s"""import scala.compiletime.*
       |import scala.compiletime.ops.any.==
       |import scala.compiletime.ops.boolean.{&&, ||}
       |import scala.compiletime.ops.int.{<, + as Add, - as Sub}
       |import scala.compiletime.ops.string.{Length, Matches, Substring, +}
       |
       |type NotB[B <: Boolean] <: Boolean = B match
       |  case true  => false
       |  case false => true
       |
       |type LowerOf[C <: String] <: String = C match
       |$lowerArms
       |  case _ => C
       |
       |$classifier
       |// the single-character string at index I
       |type CharS[S <: String, I <: Int] = Substring[S, I, Add[I, 1]]
       |
       |// the word-boundary rule of Assemble.kebab: before an upper after non-upper,
       |// and at both edges of a digit run (guarded on a preceding dash)
       |type Bnd[C <: String, P <: String] =
       |  ((IsUp[C] && NotB[IsUp[P]]) || (IsDig[C] && NotB[IsDig[P]]) ||
       |    (IsLet[C] && IsDig[P])) && NotB[P == "-"]
       |
       |type Piece[C <: String, P <: String] <: String = Bnd[C, P] match
       |  case true  => "-" + LowerOf[C]
       |  case false => LowerOf[C]
       |
       |type Go[S <: String, I <: Int, N <: Int, Acc <: String] <: String = (I < N) match
       |  case false => Acc
       |  case true  => Go[S, Add[I, 1], N, Acc + Piece[CharS[S, I], CharS[S, Sub[I, 1]]]]
       |
       |type Kebab[S <: String] <: String = Length[S] match
       |  case 0 => ""
       |  case _ => Go[S, 1, Length[S], LowerOf[CharS[S, 0]]]
       |
       |// forces a match-type application down to its constant: ops fold their operands, so the
       |// union accumulates plain singletons — unreduced applications defeat the subtype check
       |type Norm[S <: String] = S + ""
       |
       |transparent inline def dedup[Labels <: Tuple, Seen <: String]: Unit =
       |  inline erasedValue[Labels] match
       |    case _: EmptyTuple => ()
       |    case _: (h *: t) =>
       |      summonFrom:
       |        case _: (Norm[Kebab[scala.compiletime.ops.any.ToString[h]]] <:< Seen) =>
       |          error(constValue["duplicate command name: " +
       |            Kebab[scala.compiletime.ops.any.ToString[h]]])
       |        case _ =>
       |          dedup[t, Seen | Norm[Kebab[scala.compiletime.ops.any.ToString[h]]]]
       |
       |// the tuple-accumulator alternative (the library's DupNameErrAcc style): a pure match-type
       |// fold — match-type substitution instantiates `h` to the concrete singleton, and ToString
       |// normalizes away any residue so the ops in Kebab can constant-fold. Membership by
       |// ops.any.==, which folds both operands.
       |import scala.compiletime.ops.any.ToString
       |
       |type InT[X <: String, T <: Tuple] <: Boolean = T match
       |  case EmptyTuple => false
       |  case h *: t =>
       |    (X == h) match
       |      case true  => true
       |      case false => InT[X, t]
       |
       |type DedupErr[Labels <: Tuple] = DedupAcc[Labels, EmptyTuple]
       |
       |type DedupAcc[Labels <: Tuple, Seen <: Tuple] <: String = Labels match
       |  case EmptyTuple => ""
       |  case h *: t =>
       |    InT[Kebab[ToString[h]], Seen] match
       |      case true  => "duplicate command name: " + Kebab[ToString[h]]
       |      case false => DedupAcc[t, Kebab[ToString[h]] *: Seen]
       |
       |inline def dedupT[Labels <: Tuple]: Unit =
       |  inline if constValue[DedupErr[Labels] == ""] then ()
       |  else error(constValue[DedupErr[Labels]])
       |
       |inline def assertEq[S <: String, E <: String]: Unit =
       |  inline if constValue[Kebab[S] == E] then ()
       |  else error(constValue["kebab mismatch for " + S + ": got " + Kebab[S] + ", expected " + E])
       |""".stripMargin

  private def tupleType(ls: List[String]): String =
    ls.map(l => s""""$l"""").mkString("(", ", ", ")")

  private val baselineSource =
    s"""object Use:
       |  type Labels = ${tupleType(labels)}
       |""".stripMargin

  private def machineryOnlySource(classifier: String): String =
    machinery(classifier) + "\nobject Use\n"

  private def dedupSource(classifier: String, ls: List[String]): String =
    machinery(classifier) + s"""
       |object Use:
       |  dedup[${tupleType(ls)}, Nothing]
       |""".stripMargin

  private def dedupTupleSource(classifier: String, ls: List[String]): String =
    machinery(classifier) + s"""
       |object Use:
       |  dedupT[${tupleType(ls)}]
       |""".stripMargin

  private def verifySource(classifier: String): String =
    machinery(classifier) + "\nobject Use:\n" +
      labels.map(l => s"""  assertEq["$l", "${Assemble.kebab(l)}"]""").mkString("\n") + "\n"

  private def compile(src: String, silent: Boolean = true): (Double, Boolean) =
    val dir = Files.createTempDirectory("kebab-probe")
    val f   = dir.resolve("Gen.scala")
    Files.writeString(f, src)
    val out  = Files.createDirectories(dir.resolve("out"))
    val args = Array(
      "-d",
      out.toString,
      "-classpath",
      sys.props("java.class.path"),
      "-nowarn",
      "-Xmax-inlines:1000000",
      f.toString
    )
    val t0  = System.nanoTime()
    val rep =
      if silent then new Driver().process(args, new StoreReporter()) else new Driver().process(args)
    ((System.nanoTime() - t0) / 1e6, rep.hasErrors)

  private def best(src: String, reps: Int = 5): Double =
    (1 to 2).foreach(_ => compile(src)) // warm
    val runs = (1 to reps).map(_ => compile(src))
    if runs.exists(_(1)) then
      compile(src, silent = false) // show the error
      sys.error("timed source does not compile — timing invalid")
    runs.map(_(0)).min

  def main(args: Array[String]): Unit =
    // correctness first: equivalence with Assemble.kebab, per classifier
    for c <- List("regex" -> classifierRegex, "table" -> classifierTable) do
      val (_, errs) = compile(verifySource(c(1)))
      println(f"${c(0)}%-6s equivalence with Assemble.kebab over ${labels.length} labels: ${
          if errs then "FAILED" else "ok"
        }")
    // a collision that exists only after kebab-casing must be caught (error printed to console)
    val (_, dupU) = compile(dedupSource(classifierRegex, labels :+ "maxRETRIES"), silent = false)
    println(s"dup detection, union accumulator:  ${if dupU then "ok" else "MISSED"}")
    val (_, dupT) =
      compile(dedupTupleSource(classifierRegex, labels :+ "maxRETRIES"), silent = false)
    println(s"dup detection, tuple accumulator:  ${if dupT then "ok" else "MISSED"}")
    println()
    // one throwaway series so the first timed variant does not pay the compiler's JIT warmup
    best(dedupSource(classifierRegex, labels))
    println(f"${"variant"}%-16s ms (best of 5, warm driver)")
    for (name, src) <- List(
        "baseline"      -> baselineSource,
        "defs-regex"    -> machineryOnlySource(classifierRegex),
        "defs-table"    -> machineryOnlySource(classifierTable),
        "dedup50-regex" -> dedupSource(classifierRegex, labels),
        "dedup50-table" -> dedupSource(classifierTable, labels),
        "dedup50-tuple" -> dedupTupleSource(classifierRegex, labels)
      )
    do println(f"$name%-16s ${best(src)}%8.1f")
