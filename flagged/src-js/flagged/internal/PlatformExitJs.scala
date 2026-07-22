package flagged.internal

import scala.scalajs.js

/** Process termination for `parseOrExit`. Scala.js has no `System.exit`: under Node the process
  * exits; in runtimes without a `process` (browsers) termination is unsupported.
  */
private[flagged] object PlatformExit:
  def exit(code: Int): Nothing =
    val process = js.Dynamic.global.process
    if js.typeOf(process) != "undefined" then process.exit(code)
    throw new UnsupportedOperationException(s"exit($code): this runtime has no process to exit")
