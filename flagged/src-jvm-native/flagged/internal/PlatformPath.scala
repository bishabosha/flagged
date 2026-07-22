package flagged.internal

import java.nio.file.{InvalidPathException, Path, Paths}
import flagged.{Parser, Result}
import Result.eval

/** The path instance is shared by the platforms that have `java.nio.file` (JVM and Scala Native);
  * defined once here and re-exposed by each platform's [[PlatformValues]].
  */
private[internal] def path: Parser.Value[Path] = Parser.of("path"): s =>
  Result:
    try Paths.get(s)
    catch case _: InvalidPathException => eval.raise(s"'$s' is not a valid path")
