package bench.defs

import flagged.{run, short, positional, Parser}
import mainargs.{main, arg, Flag, ParserForMethods}

// ---- method-based commands: flagged `@run` vs mainargs `@main` ----------------
// Bodies fold every parameter into the result so invocation cannot be elided.

/** Lone command method — the same grammar as the `simple` scenario. */
object FMethodApp:
  @run def send(
      @short('f') foo: String = "x",
      bar: Int = 0,
      @short('b') baz: Boolean = false,
      qux: List[String] = Nil
  ): Int = foo.length + bar + (if baz then 1 else 0) + qux.size

object MMethodApp:
  @main def send(
      @arg(short = 'f') foo: String = "x",
      bar: Int = 0,
      @arg(short = 'b') baz: Flag = Flag(),
      qux: Seq[String] = Nil
  ): Int = foo.length + bar + (if baz.value then 1 else 0) + qux.size

/** Three commands dispatching on the first token — the compile bench's `commands` interface. */
object FMethodCli:
  @run def add(@positional name: String, url: String = ""): Int          = name.length + url.length
  @run def remove(@positional name: String, force: Boolean = false): Int =
    name.length + (if force then 1 else 0)
  @run def ls(verbose: Boolean = false, limit: Int = 10): Int = limit

object MMethodCli:
  @main def add(name: String, url: String = ""): Int        = name.length + url.length
  @main def remove(name: String, force: Flag = Flag()): Int =
    name.length + (if force.value then 1 else 0)
  @main def ls(verbose: Flag = Flag(), limit: Int = 10): Int = limit

object MethodDefs:
  val flaggedApp  = Parser.method(FMethodApp)
  val flaggedCli  = Parser.methods(FMethodCli)
  val mainargsApp = ParserForMethods(MMethodApp)
  val mainargsCli = ParserForMethods(MMethodCli)
