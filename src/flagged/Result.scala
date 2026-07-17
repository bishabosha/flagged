package flagged

/** flagged uses [[steps.result.Result]] (lampepfl/steps) as its error channel. Re-exported here so
  * `import flagged.*` is enough to pattern-match parse results.
  */
export steps.result.Result
export steps.result.Result.{Ok, Err}

/** The error channel of a parse: either the user asked for help, or the arguments were invalid.
  */
enum ParseError:
  /** The user asked for `--help` / `-h`; `text` is the rendered help screen. */
  case Help(text: String)

  /** Parsing failed. `message` describes the problem, `hint` tells the user how to get help. */
  case Failure(message: String, hint: String)

/** Outcome of parsing a command line. */
type ParseResult[A] = Result[A, ParseError]
