package claw

/** Outcome of parsing a command line. */
enum Result[+A]:
  /** Parsing succeeded. */
  case Ok(value: A)

  /** The user asked for `--help` / `-h`; `text` is the rendered help screen. */
  case Help(text: String)

  /** Parsing failed. `message` describes the problem, `hint` tells the user how to get help. */
  case Failure(message: String, hint: String)

  def map[B](f: A => B): Result[B] = this match
    case Ok(a)         => Ok(f(a))
    case Help(t)       => Help(t)
    case Failure(m, h) => Failure(m, h)

  def flatMap[B](f: A => Result[B]): Result[B] = this match
    case Ok(a)         => f(a)
    case Help(t)       => Help(t)
    case Failure(m, h) => Failure(m, h)

  /** `Right(value)` on success, `Left(text-to-show)` for both help and failure. */
  def toEither: Either[String, A] = this match
    case Ok(a)          => Right(a)
    case Help(t)        => Left(t)
    case Failure(m, h)  => Left(if h.isEmpty then m else s"$m\n$h")

  def fold[B](onOk: A => B, onHelp: String => B, onFailure: (String, String) => B): B = this match
    case Ok(a)         => onOk(a)
    case Help(t)       => onHelp(t)
    case Failure(m, h) => onFailure(m, h)
