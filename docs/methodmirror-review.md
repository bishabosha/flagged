# MethodMirror review

A design review of `flagged.meta.MethodMirror` / `MethodsMirror` (August 2026, branch
`mutable-spec`), compared against its prior art —
[ops-mirror](https://github.com/bishabosha/scalar-2025/tree/main/ops-mirror) (0.1.2), which
reflects *abstract* trait methods — and against the annotation-heavy method-reflection
libraries of the wider ecosystem: cask, mainargs, case-app, airframe-launcher, picocli, and
the generic annotation facilities (shapeless-3 `Annotations`, Magnolia).

Every defect below was verified empirically: probe programs compiled and run against the
built `flagged.jvm` module. Reproduction snippets are inlined so the findings stay
self-contained.

## Summary

`MethodMirror` industrializes the ops-mirror idea: structure as refined type members, plus
exactly the term residue types cannot carry (`invoke`, `Defaults`). The `ArgumentList`
annotation encoding makes the whole downstream pipeline macro-free inline code — the
architectural advance over ops-mirror, whose `AnnotatedType` encoding forces every consumer
to be another macro. The evolution traded away three expressive powers the prior art had:
**abstract/instance receivers**, **arbitrary annotation expressions**, and **type-carrying
annotations**. Six concrete defects were verified, two of them silent misbehaviors and one a
macro crash on code ops-mirror handled correctly.

## What MethodMirror does better than ops-mirror

- **Term residue where types can't reach.** ops-mirror is purely phantom — one cast
  instance, no members; consuming metadata requires a second macro (`OpsMirror.metadata`
  decodes `AnnotatedType` back into `Expr[Any]`), so every downstream typeclass must itself
  be a macro. `MethodMirror` carries `invoke` and `Defaults`, and encodes annotations as
  constants that `AnnotMirror.find` / match types materialise with zero further macros —
  `DeriveMethods` is ordinary inline code shared with the case-class path.
- **Defaults are mirrored**, with an explicit error when a getter is missing
  (`MethodMacros.scala:90`). ops-mirror ignores defaults entirely.
- **Varargs**: normalised to `Seq[E]` in the type members and re-splatted in `invoke`
  (`MethodMacros.scala:77`, `:105`), matching `Mirror`'s treatment of vararg case-class
  fields. ops-mirror has no vararg handling.
- **Nesting**: one-level `Entry.Scope` descent, the nested tower summoned once
  (`MethodEntry.EntriesResults.ScopeNode`). ops-mirror is flat.
- **Opt-in membership** via `Reflectable` subtyping. ops-mirror mirrors *every* declared
  method, and its check for unknown annotations is commented out (`OpsMirror.scala:129`),
  with a special-case filter for `SourceFile`.
- **Guarded synthesis**: the `needsOuter` check (`MethodMacros.scala:32`) turns what would
  be an erasure crash into a diagnostic, and is tested. ops-mirror does
  `tpe.classSymbol.get` and would crash on non-class types.
- **Typed results**: `MirroredResult` and the union-typed `MethodEntry#Out` give a group's
  parse result a precise type where mainargs gives `Any`.

Worth adopting from the prior art regardless: ops-mirror's `@implicitNotFound` points users
at a directly callable diagnostic entry (`OpsMirror.reify[T]`). See defect 6 for why
`MethodEntry` / `MethodsMirror` want the same.

## What was lost relative to ops-mirror

1. **Abstract methods / receiver polymorphism — the headline of the prior art.** ops-mirror
   mirrors *traits*, so one definition derives both servers and clients. `MethodMirror.invoke`
   takes no receiver and the macro enforces a static object (`MethodMacros.scala:237`). Right
   call for a CLI runner — but `flagged.meta` presents itself as the compiler-intrinsic
   candidate (see `AnnotMirror`'s doc), and an intrinsic that cannot mirror an interface
   cannot serve the tapir/RPC-shaped use cases the prior art was invented for. A variant whose
   invoker takes `(receiver: T, args)` would subsume both.
2. **Arbitrary annotation payloads.** ops-mirror stores the annotation *tree*
   (`AnnotatedType(Meta, annot)`), so `@get("/greet/{name}")`, enum-valued annotations, and
   computed arguments all work. `ArgumentList` accepts only case-class `StaticAnnotation`s
   applied with literal constants — and the failure mode is silent (defect 1).
3. **Type-carrying annotations.** ops-mirror's `ErrorAnnotation[E]` flows into
   `Operation#ErrorType`; `ArgumentList` has value columns only, so an annotation cannot
   attach a *type* to a member. flagged itself doesn't need an error channel (`Result`/`emap`
   cover it), but as a general facility this is a real expressiveness gap.

## Verified defects

Ordered by severity: silent wrong behavior first, then crashes, then diagnostics quality.

### 1. Non-`Literal` constant annotation args silently un-command a method

```scala
object app:
  final val label = "run"                       // constant-typed, but an Ident tree
  @cmd(name = label) def go(@opt x: Int): Int = x
  @cmd def other(): Int                         = 0
```

The *entire* `@cmd` occurrence is dropped — `constArg` matches only `Literal` trees, so
`resolveArgs` sets `ok = false` and `annType` yields nothing
(`AnnotationMacros.scala:88`). `go` silently vanishes from the parser: help lists only
`other`; `go` is "unknown command". The argument even has a constant *type* here
(`label.type = ("run" : String)`), encodable in principle from `t.tpe` — the tree-shape
restriction wins. At minimum, a `Reflectable`-deriving annotation that fails to encode
should `errorAndAbort`, not disappear.

### 2. Inherited `@cmd` methods are silently invisible

```scala
trait base:
  @cmd def shared(x: Int): Int = x
object app extends base:
  @cmd def own(): Int = 0        // help shows only `own`; `shared` is unknown
```

`reflectableMembers` reads `moduleClass.declarations` (`MethodMacros.scala:52`), which
excludes inherited members. A mixin is exactly how a user would share common subcommands
across apps. cask uses `memberMethods` and inherits routes as a matter of course; picocli
supports the whole axis (subclassing, `scope = INHERIT`, `@Mixin`). Either walk base
classes, or error when an inherited member carries a `Reflectable` annotation.

### 3. Bare `def now: String` (no parens) crashes the macro

```scala
object app:
  @cmd def now: String = "hi"    // scala.MatchError at MethodMacros.scala:163
```

The `ExprType` result is never stripped, and the quoted pattern `'[r]` in the
`runtimeChecked` refinement match won't match a non-value type, surfacing as a raw
`MatchError` inside implicit search. The comment at `MethodMacros.scala:71` says
parameterless defs are intended to work (`def m()` does). ops-mirror handled exactly this
case with its `ByNameType(res)` branch — a regression against the prior art.

### 4. A lone `using` clause becomes CLI input

```scala
object app:
  @cmd def go(using s: String): Int = s.length
// Parser.method(app).parse(Seq("hello")) == Ok(5)
```

Multiple parameter lists are rejected, but a single implicit list is not detected
(`MethodMacros.scala:61`): context parameters are parsed positionally from argv. Either
reject, or follow cask's pattern — its arity-0 readers (`NoOpParser`, `ParamReader` for
`cask.Request`) bind context-shaped parameters from the environment rather than from input.
The flagged analogue: skip contextual lists and `summonInline` them in the invoker.

### 5. Overloads defeat the compile-time duplicate check

```scala
object app:
  @cmd def go(x: Int): Int       = x
  @cmd def go(x: String): String = x
// compiles; IllegalArgumentException("duplicate command name 'go'") at construction
```

`Derive.checkSumRules[CommandSlots[ER]]` sees only annotation-provided names, never the
derived `MirroredLabel`s — yet labels are always constants, so the check could cover them.
Cheapest fix: detect same-name reflectable members in `reflectableMembers` and abort with a
position.

### 6. `@cmd private def` produces a ~100-line implicit-search tree dump

The macro happily mirrors the private method; the spliced `app.secret(...)` then fails
re-typing at the summon site, surfacing as "given instance … does not match type" plus the
entire synthesised anonymous class. The class-nested-object case gets a clean diagnostic
("rejected, not crashed on" in `MethodsSuite`); private members deserve the same — check
`Flags.Private` and abort with a real message (`private[scope]` visible at the summon site
is the one nuance). cask demonstrates the general discipline: it performs implicit search
*inside* the macro (`summonReader`, `convertToResponse`) specifically because errors from
expanded code "would be completely off" position-wise — pre-validate in the macro
everything the expansion will need, accessibility included. An `@implicitNotFound` on
`MethodEntry`/`MethodsMirror` naming a diagnostic entry point would soften every failure of
this class.

### Checked and fine

Same-list dependent defaults (`def f(x: Int, y: Int = x)`) are ill-formed Scala, so the
`m$default$N` getter scheme is safe. Multi-list and polymorphic methods get clean positioned
errors. `@cmd` on a `val` is silently ignored by the membership filter — arguably deserves
an error, but minor.

## The ecosystem: three annotation-encoding models

Every serious annotation-driven method-reflection library in Scala keeps annotations (or
their arguments) available at the *term* level. flagged is alone at the constants-in-types
extreme:

| | encoding | args | annotation can… | consumers |
| --- | --- | --- | --- | --- |
| cask | term spliced into runtime code | arbitrary expressions | carry behavior, wrap invocation | plain runtime code |
| ops-mirror | term captured in `AnnotatedType` | arbitrary expressions | carry types (`ErrorAnnotation[E]`) | must be macros |
| flagged | constant columns in types (`ArgumentList`) | literals only | inert record | inline/match types, zero-alloc |

That position is what makes the macro-free inline pipeline possible, so it is worth keeping
— but it needs two compensations: fail loudly when an annotation cannot be encoded
(defect 1), and leave a term-level escape hatch (a runtime override hook, or a residue slot
for non-constant args) so the static encoding is a fast path rather than a wall.

### cask (closest production relative)

Cask's `@get`/`@post`/`@loggedIn` extend `Decorator` — a trait with behavior
(`wrapFunction`) and a type member (`InputParser[T]`) selecting the reader typeclass family
per parameter list. The macro splices the annotation tree directly into runtime code
(`RoutesEndpointMetadata.scala`): the annotation instance *is* the interceptor, and
decorators compose as a stack whose inner/outer types are checked in-macro with positioned
errors. Two designs worth noting:

- **Multiple parameter lists are a feature**: each list is bound by the corresponding
  decorator in the stack — `@loggedIn` supplies the second list's arguments. flagged
  rejects multi-lists; this is the natural design slot for non-CLI-supplied parameters
  (env/config/context) if ever wanted.
- **Instance receivers**: `EntryPoint[Cls, Any]` with `invoke0 = (clazz: Cls, ...)` and
  defaults as `Cls => Any` getters. Routes are classes — state, DI, testability.

Where flagged is well ahead of cask: cask's docs are never wired up (`doc = None // TODO`
twice); default getters are recovered by regex-matching `name$default$N` against
`method.owner.tree` — same-compilation-unit only, where flagged's symbol-based
`methodMember` lookup is cross-unit safe; binding goes through `Map[String, Any]` with
`asInstanceOf` at every join and results erased to `Any`; and there is no compile-time
collision checking of routes at all.

### The rest of the field

- **mainargs**: object receivers only (documented); annotation values effectively
  static-only, but with documented runtime escape hatches (`customName`/`customDoc`) for
  dynamic metadata — flagged has no override channel at all. Results typed `Any` where
  flagged unions precisely.
- **case-app**: case-class fields, annotation values are runtime terms — no constant
  restriction.
- **airframe-launcher / picocli**: instance-based (Surface instantiates the class; picocli
  populates fields) — DI, per-invocation state. picocli adds the full reuse axis:
  subclassing, `scope = INHERIT`, `@Mixin` (cf. defect 2).
- **shapeless-3 `Annotations` / Magnolia `param.annotations`**: the generic prior art hands
  out runtime *values* of arbitrary annotation expressions — no case-class, no
  `derives Defaults`, no constant restriction — at the cost of allocation and no type-level
  dispatch. `AnnotMirror` is genuinely novel in occupying the other corner, but the
  three-way entry fee (case class + literal constants + `derives Defaults`) is the steepest
  in the ecosystem, and defect 1 makes the fee silently fatal.
- **tapir annotations / Caliban / jsoniter-scala / uPickle**: all read annotations at
  derivation site inside their own macro, so none needed a reusable encoding — which is why
  `AnnotMirror`/`MethodMirror` as a shared facility is the interesting contribution. Their
  users routinely write `@description(constants.helpText)`-style references; that is the
  first thing an adopter of the meta layer will hit (defect 1 again).
- **Nobody in the Scala field reads scaladoc as help text** (Rust's clap does).
  `Symbol.docstring` is reachable in the macro for same-unit / `-Yretain-trees` cases — a
  possible differentiator, with the cross-unit caveat.

## Recommendations, ranked

1. **Defects 1 and 2** (silent wrong behavior): error on unencodable `Reflectable`
   annotations; include or reject inherited `@cmd` members.
2. **Defect 3** (crash on reasonable code): strip `ExprType` for bare parameterless defs.
3. **Defects 6 and 5** (diagnostics): pre-check access and same-name members in the macro;
   `@implicitNotFound` on the summoned traits.
4. **Defect 4** (needs a design decision): reject `using` clauses, or bind them from context
   cask-style.
5. **Strategic**, if `flagged.meta` is to be the intrinsic candidate beyond CLIs: receiver
   polymorphism (`invoke(receiver, args)` variant subsuming ops-mirror's abstract-trait use
   case) and a type-argument column in `ArgumentList` — both change `MethodMirror`'s shape,
   so they should be decided before the encoding calcifies. New on this list via cask:
   decorator-style behavioral annotations and decorator-bound parameter lists.
