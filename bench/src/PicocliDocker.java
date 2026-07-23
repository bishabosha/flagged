package bench.defs;

import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** The realistic docker-style CLI (see RealisticDefs.scala) in picocli's annotation model. */
@Command(
    name = "docker",
    subcommands = {PicocliDocker.Run.class, PicocliDocker.Pull.class, PicocliDocker.Ps.class})
public class PicocliDocker {

  @Command(name = "run")
  public static class Run {
    @Option(names = {"--name"}) public String name;
    @Option(names = {"-e", "--env"}) public List<String> env = new ArrayList<>();
    @Option(names = {"-p", "--publish"}) public List<String> publish = new ArrayList<>();
    @Option(names = {"-v", "--volume"}) public List<String> volume = new ArrayList<>();
    @Option(names = {"-l", "--label"}) public List<String> label = new ArrayList<>();
    @Option(names = {"-w", "--workdir"}) public String workdir;
    @Option(names = {"-u", "--user"}) public String user;
    @Option(names = {"--entrypoint"}) public String entrypoint;
    @Option(names = {"--network"}) public String network = "default";
    @Option(names = {"--restart"}) public String restart = "no";
    @Option(names = {"-m", "--memory"}) public String memory;
    @Option(names = {"--cpus"}) public Double cpus;
    @Option(names = {"--pull"}) public String pull = "missing";
    @Option(names = {"-d", "--detach"}) public boolean detach;
    @Option(names = {"--rm"}) public boolean rm;
    @Option(names = {"-i", "--interactive"}) public boolean interactive;
    @Option(names = {"-t", "--tty"}) public boolean tty;
    @Option(names = {"--read-only"}) public boolean readOnly;
    @Parameters(index = "0") public String image;
    @Parameters(index = "1..*") public List<String> cmd = new ArrayList<>();
  }

  @Command(name = "pull")
  public static class Pull {
    @Option(names = {"--platform"}) public String platform;
    @Option(names = {"-q", "--quiet"}) public boolean quiet;
    @Option(names = {"-a", "--all-tags"}) public boolean allTags;
    @Parameters(index = "0") public String image;
  }

  @Command(name = "ps")
  public static class Ps {
    @Option(names = {"-a", "--all"}) public boolean all;
    @Option(names = {"-q", "--quiet"}) public boolean quiet;
    @Option(names = {"-f", "--filter"}) public List<String> filter = new ArrayList<>();
    @Option(names = {"-n", "--last"}) public int last = -1;
    @Option(names = {"--format"}) public String format;
  }
}
