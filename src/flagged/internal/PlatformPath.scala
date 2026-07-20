//> using target.platform jvm scala-native
package flagged.internal

import java.nio.file.{InvalidPathException, Path, Paths}
import flagged.{Parser, Ok, Err}

/** The path instance is shared by the platforms that have `java.nio.file` (JVM and Scala Native);
  * defined once here and re-exposed by each platform's [[PlatformValues]].
  */
private[internal] def path: Parser.Value[Path] = Parser.of("path")(s =>
  try Ok(Paths.get(s))
  catch case _: InvalidPathException => Err(s"'$s' is not a valid path")
)
