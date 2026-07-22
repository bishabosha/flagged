package flagged.internal

/** Process termination for `parseOrExit`. The JVM and Native have `sys.exit`. */
private[flagged] object PlatformExit:
  def exit(code: Int): Nothing = sys.exit(code)
