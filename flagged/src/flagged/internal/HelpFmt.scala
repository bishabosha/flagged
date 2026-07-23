package flagged.internal

/** Renders `--help` screens and usage lines. */
private[flagged] object HelpFmt:

  def render(cmd: Command, prog: String, path: List[String], showHidden: Boolean = false): String =
    val full = (prog :: path).mkString(" ")
    val b    = new StringBuilder

    cmd.version.foreach { v =>
      b ++= s"$full ${v()}"
      b ++= "\n\n"
    }

    if cmd.description.nonEmpty then
      b ++= cmd.description
      b ++= "\n\n"

    b ++= "Usage: "
    b ++= usageLine(cmd, full)
    b += '\n'

    cmd.sub.foreach { g =>
      b ++= "\nCommands:\n"
      val cases = if showHidden then g.cases else g.cases.filterNot(_.hidden)
      b ++= table(cases.map { c =>
        val default = if g.defaultCase.exists(_.name == c.name) then " (default)" else ""
        val hidden  = if showHidden && c.hidden then " (hidden)" else ""
        c.name -> s"${c.help}$default$hidden".stripLeading()
      })
      b += '\n'
    }

    if cmd.positionals.nonEmpty then
      b ++= "\nArguments:\n"
      b ++= table(cmd.positionals.map(p => s"<${p.name}>" -> withExtras(p.help, posExtras(p))))
      b += '\n'

    cmd.trailing.filter(_.help.nonEmpty).foreach { t =>
      b ++= "\nArguments after --:\n"
      b ++= table(Seq("-- <args>" -> t.help))
      b += '\n'
    }

    val visible                 = if showHidden then cmd.opts else cmd.opts.filterNot(_.hidden)
    val (ungrouped, inSections) = visible.partition(_.group.isEmpty)
    val hasHidden          = cmd.opts.exists(_.hidden) || cmd.sub.exists(_.cases.exists(_.hidden))
    def optRow(o: OptSpec) = optLeft(o) -> withExtras(o.help, optExtras(o, showHidden))

    b ++= "\nOptions:\n"
    val optRows =
      ungrouped.map(optRow) ++
        Seq("-h, --help" -> "Show this message and exit") ++
        (if hasHidden then Seq("    --help-all" -> "Show this message with hidden options and exit")
         else Nil) ++
        cmd.version.map(_ => "    --version" -> "Show version and exit")
    b ++= table(optRows)
    b += '\n'

    // sections in first-appearance order
    inSections.map(_.group.get).distinct.foreach { g =>
      b ++= s"\n$g options:\n"
      b ++= table(inSections.filter(_.group.contains(g)).map(optRow))
      b += '\n'
    }

    cmd.sub.foreach { _ =>
      b ++= s"\nRun '$full <command> --help' for more information on a command.\n"
    }

    b.result().stripSuffix("\n")

  def usageLine(cmd: Command, full: String): String =
    val parts = List.newBuilder[String]
    parts += full
    parts += "[options]"
    cmd.positionals.foreach { p =>
      p.mode match
        case Mode.Repeated(_, _, _) => parts += s"[<${p.name}>...]"
        // a positional product's metavar is pre-bracketed (`<x> <y>`)
        case Mode.Product(_, _) =>
          parts += (if isRequiredPos(p) then p.metavar else s"[${p.metavar}]")
        case _ if isRequiredPos(p) => parts += s"<${p.name}>"
        case _                     => parts += s"[<${p.name}>]"
    }
    cmd.sub.foreach { g =>
      parts += (
        if g.optional || g.default.nonEmpty || g.defaultCase.nonEmpty then "[<command>]"
        else "<command>"
      )
    }
    cmd.trailing.foreach { _ => parts += "[-- <args>]" }
    parts.result().mkString(" ")

  private def isRequiredPos(p: PosSpec): Boolean =
    p.default.isEmpty && (p.mode match
      case Mode.Single(_, optional)  => !optional
      case Mode.Product(_, optional) => !optional
      case _                         => false)

  private def optLeft(o: OptSpec): String =
    val short = o.short.map(c => s"-$c, ").getOrElse("    ")
    val value = o.mode match
      case Mode.Flag(_, _)        => ""
      case Mode.Single(_, _)      => s" <${o.metavar}>"
      case Mode.Product(_, _)     => s" ${o.metavar}" // pre-bracketed: `<x> <y>`
      case Mode.Repeated(_, _, _) => s" <${o.metavar}>"
    s"$short--${o.long}$value"

  private def optExtras(o: OptSpec, showHidden: Boolean): List[String] =
    val default = o.default.map(d => d()).filterNot { v =>
      // a flag default equal to the absent-value (fromCount(0)) conveys nothing
      o.mode match
        case Mode.Flag(parser, _) => parser.fromCount(0).toOption.contains(v)
        case _                    => false
    }
    val dflt = default match
      case Some(v) => fmtDefault(v).map(s => s"default: $s")
      case None    => None
    val required = o.mode match
      case Mode.Single(_, optional)  => o.default.isEmpty && !optional
      case Mode.Product(_, optional) => o.default.isEmpty && !optional
      case _                         => false
    val repeatable = o.mode match
      case Mode.Repeated(_, _, _) => true
      case _                      => false
    val alias = Option.when(o.aliases.nonEmpty)(
      s"alias: ${o.aliases.map("--" + _).mkString(", ")}"
    )
    List(
      dflt,
      Option.when(required)("required"),
      Option.when(repeatable)("repeatable"),
      alias,
      Option.when(showHidden && o.hidden)("hidden")
    ).flatten

  private def posExtras(p: PosSpec): List[String] =
    p.default.map(d => d()).flatMap(fmtDefault).map(s => s"default: $s").toList

  /** Human-friendly rendering of a default value; `None` means "don't show". */
  private def fmtDefault(v: Any): Option[String] = v match
    case None                   => None
    case Some(x)                => Some(x.toString)
    case false                  => None
    case s: Seq[?] if s.isEmpty => None
    case s: Seq[?]              => Some(s.mkString(","))
    case other                  => Some(other.toString)

  private def withExtras(help: String, extras: List[String]): String =
    if extras.isEmpty then help
    else if help.isEmpty then extras.mkString("(", ", ", ")")
    else s"$help ${extras.mkString("(", ", ", ")")}"

  private def table(rows: Seq[(String, String)]): String =
    val width = rows.map(_(0).length).maxOption.getOrElse(0)
    rows
      .map { (left, right) =>
        if right.isEmpty then s"  $left"
        else s"  ${left.padTo(width, ' ')}  $right"
      }
      .mkString("\n")
