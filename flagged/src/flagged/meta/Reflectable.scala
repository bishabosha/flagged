package flagged.meta

import scala.annotation.StaticAnnotation

/** Base trait for annotations that mark a member as reflectable: [[MethodsMirror]] mirrors exactly
  * the methods and nested objects annotated with an annotation deriving from `Reflectable`. The
  * mirror layer is otherwise agnostic to which annotation opted the member in — flagged's own
  * marker is [[flagged.cmd]].
  */
trait Reflectable extends StaticAnnotation
