package bench.defs

import flagged.{cmd, opt, Parser}
import mainargs.{main, arg, Flag, ParserForMethods}

// ---- method-based commands: flagged `@cmd` vs mainargs `@main` ----------------
// Bodies fold every parameter into the result so invocation cannot be elided.

/** Lone command method — the same grammar as the `simple` scenario. */
object FMethodApp:
  @cmd def send(
      @opt(short = 'f') foo: String = "x",
      @opt bar: Int = 0,
      @opt(short = 'b') baz: Boolean = false,
      @opt qux: List[String] = Nil
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
  @cmd def add(name: String, @opt url: String = ""): Int =
    name.length + url.length
  @cmd def remove(name: String, @opt force: Boolean = false): Int =
    name.length + (if force then 1 else 0)
  @cmd def ls(@opt verbose: Boolean = false, @opt limit: Int = 10): Int = limit

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
