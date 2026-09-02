# mcstage

[![build](https://github.com/SalyyS1/mcstage/actions/workflows/build.yml/badge.svg)](https://github.com/SalyyS1/mcstage/actions/workflows/build.yml)

mcstage boots a real Minecraft server, installs your plugin, types commands into its console, and tells
you whether the lines you expected showed up. It downloads the server itself, verifies the jar against
the digest its publisher printed, and reuses it forever after, so the whole thing is one command and one
YAML file:

```
$ mcstage test mcstage.yml --accept-eula
== paper 1.21.4
   ok   boot
   ok   send "warp reload"
   ok   expect contains "reloaded 4 warps"
== purpur 1.21.4
   ok   boot
   ok   send "warp reload"
   FAIL expect contains "reloaded 4 warps"
        nothing contains "reloaded 4 warps" within 20.0s

Summary: 1 passed, 1 failed in 53.2s
```

Exit code 1, a report you can point at, and the instance directories left on disk with the servers' own
logs in them.

## Why this exists

Testing a Minecraft plugin properly means running it on a server, and the tools around that problem each
solve a different half of it. MockBukkit gives you an API to assert against but never starts a server, so
the failures it cannot have are exactly the interesting ones: a plugin that will not enable on one
version, a command that throws on Folia, a shutdown that hangs. `run-task` boots a real server and does
it well, but it is a Gradle plugin, so it belongs to one build tool and one project layout. The Docker
images are excellent at running a server and are aimed at the person operating it, not the person changing
the code. And a version matrix — the actual question, "does this still work on the four versions I
support" — is usually a person doing it by hand, four times, badly.

mcstage is the standalone CLI in the middle: no build tool, no daemon, no container, no mocks. It talks to
the server the way you do, over its console.

## Getting started

You need Java 21 or newer to run mcstage. The server you test may want a different Java than the one
mcstage runs on; `--java` and `MCSTAGE_JAVA` exist for that.

Download `mcstage.jar` from the releases page and put it somewhere on your PATH, or wrap it:

```sh
java -jar mcstage.jar --help
```

Then write a scenario, or have one written for you:

```sh
mcstage init                       # writes mcstage.yml
mcstage test mcstage.yml --accept-eula
```

The first run downloads a server jar — around 50 MB — and generates a world, so it takes a minute. Every
run after that reuses both. On this machine the same Paper case took 44 seconds the first time and 22 the
second.

## A scenario

```yaml
name: warps survive a reload
platforms: [paper, purpur]
versions: ["latest", "1.21.4"]
memory: 2G

plugins:
  - build/libs/*.jar

forbid:
  - "Could not load 'plugins"
  - re:Exception in thread "Server thread"

timeout: 20s

steps:
  - enabled: Warps
  - send: "warp set spawn"
  - expect: "set warp spawn"
  - send: "warp reload"
  - expect: "reloaded 1 warp"
  - wait: 5s
```

Two platforms and two versions is four servers, run one after another. `docs/scenarios.md` documents every
key; the short version is that a step is one of `expect`, `send`, `wait` or `enabled`, a string is a
substring unless it starts with `re:`, and anything in `forbid` is checked against every line for the whole
run rather than at one point in it.

## The commands

| Command | What it does |
| --- | --- |
| `mcstage test <file>` | Runs a scenario against every platform and version it names. This is the CI command. |
| `mcstage run <dir>` | Boots an instance that already exists. With `--expect` it asserts; with `--attach` it hands you the console. |
| `mcstage create <dir>` | Downloads a build and lays out an instance, without starting it. |
| `mcstage versions [platform]` | What Paper, Folia and Purpur currently publish. |
| `mcstage cache` | What has been downloaded, and `--clear` to be rid of it. |
| `mcstage init [file]` | Writes a scenario to start from. |

The development loop that is not a test run is `run --attach`:

```sh
mcstage create dev --platform paper --version 1.21.4 --accept-eula
mcstage run dev --plugin build/libs/warps-1.4.jar --attach
```

That boots the instance, copies the jar you just built into `plugins/`, prints the server's output, and
forwards what you type to its console. It is the server directory and start script you would otherwise keep
by hand, without keeping them.

## What it decides, and what it only reports

mcstage fails a case for three reasons: a step was not satisfied in time, the server died, or a line the
scenario forbade appeared. That is the whole list, and everything else it notices is reported as a number
rather than turned into a verdict.

This matters more than it sounds. A stock Paper 1.21.4 with no plugins at all prints four warnings and one
error on a normal boot — the warnings are the offline-mode banner, and the error is Paper's own version
check failing against an endpoint that has since moved:

```
[Server thread/WARN]: **** SERVER IS RUNNING IN OFFLINE/INSECURE MODE!
[Thread-5/ERROR]: [PaperVersionFetcher] Error while parsing version
java.io.FileNotFoundException: https://api.papermc.io/v2/projects/paper/versions/1.21.4
```

A harness that failed the build on any `ERROR` line would fail on a clean server, so people would turn it
off, and then it would catch nothing. mcstage counts those lines, prints the counts, and leaves the decision
about which of them matter to the `forbid` list in your scenario, where you can see it and change it.

## The EULA is yours to accept

A Minecraft server will not start until `eula.txt` says `eula=true`, and that line means somebody agreed to
Mojang's terms. mcstage writes it only when you pass `--accept-eula`, and it never uses the JVM property
that bypasses the file. Without the flag the instance is still created and the run stops with exit code 2
and a sentence saying which file is missing and why.

## Test servers stay off the network

An instance mcstage creates gets `server-ip=127.0.0.1` and `online-mode=false`. The second setting is why
the first one is not optional: a server that does not authenticate anybody, listening on an address the
network can reach, is a server anyone nearby can join as any name. Paper says so itself in four warning
lines every boot, which is where those four warnings above came from.

If you pass `--bind` something other than loopback while offline mode is on, mcstage prints what that
means. It does not stop you — it is your machine — but it does not do it quietly either. `enable-rcon` and
`enable-query` are written as `false`, because commands go in over the process's own standard input and no
port needs opening for that.

Keys you set yourself are left alone when an instance is re-created, so an edit made to test something
survives a move to a newer build. Only `server-port` and `server-ip` are rewritten every time, because
those two belong to mcstage.

## The jar cache

Downloaded server jars live in `~/.mcstage/jars` — or `$MCSTAGE_HOME/jars`, which is what to point at a
cache your CI restores between builds — laid out by digest:

```
~/.mcstage/jars/paper/sha256-5ee4f542.../paper-1.21.4-232.jar
~/.mcstage/jars/purpur/md5-e3832b46.../purpur-1.21.4-2416.jar
```

The digest is the identity, so two projects testing the same build share one file, and a build that is
republished under the same number lands somewhere new instead of overwriting what was already verified.
Every jar is re-hashed on the way out of the cache as well as on the way in; a cache that is not
re-verified is just a directory of files whose contents nobody has checked.

Paper's API publishes a SHA-256 and addresses the download by it. Purpur publishes an MD5, so that is what
gets checked for Purpur, and mcstage says which algorithm it used rather than implying the two are equally
strong. If a download does not match, the file is deleted and the run stops.

`--offline` turns the network off entirely: anything already cached works, anything else is an error that
names what is missing.

## Exit codes

| Code | Meaning |
| --- | --- |
| 0 | Every case passed. |
| 1 | A server ran and disagreed with the scenario. |
| 2 | Nothing was tested: no such version, a download that failed, no Java, an unaccepted EULA, a scenario that does not parse. |

The difference between 1 and 2 is the point. A job that cannot tell "the plugin is broken" from "the
download failed" will eventually be ignored for both.

## In CI

```yaml
name: plugin
on: [push, pull_request]

jobs:
  servers:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 21 }
      - run: ./gradlew build
      - uses: actions/cache@v4
        with:
          path: ~/.mcstage/jars
          key: mcstage-jars-${{ hashFiles('mcstage.yml') }}
      - run: java -jar mcstage.jar test mcstage.yml --accept-eula --format json -o mcstage-report.json
      - uses: actions/upload-artifact@v4
        if: always()
        with: { name: mcstage-report, path: mcstage-report.json }
```

Caching `~/.mcstage/jars` is worth doing: it is the difference between a minute of downloading per run and
none. The `--accept-eula` in a workflow file is a real agreement made by whoever owns the repository, which
is why it is written there in the open rather than defaulted somewhere.

## What it does not do

- **No proxies.** The fill API serves Velocity too, and mcstage does not offer it. A proxy has no world, no
  EULA and no `server.properties`, and its generated configuration binds to every interface, so supporting
  one means a second creation path with different assumptions. It is not here until it has been run against.
- **No client.** Nothing here logs in or plays. If your plugin needs a player to be tested, mcstage gets you
  as far as a running server with the plugin enabled and a console you can drive.
- **No mocks.** The point is the real jar.
- **No opinion about which Java a version needs.** That mapping changes with every Minecraft release, and it
  is the server's own error message when it is wrong. mcstage reports the Java it detected with every case
  and lets the server refuse for itself, next to the version that caused it.
- **No advice.** It reports what happened and does not suggest what to change.

If you want the other direction — reading what a server has already produced, or what is inside a plugin jar
— that is what [serverdoctor](https://github.com/SalyyS1/serverdoctor), [pluginscope](https://github.com/SalyyS1/pluginscope)
and [foliascan](https://github.com/SalyyS1/foliascan) are for.

## How the console actually works

Commands are written to the server process's standard input, which is worth explaining because it is the
part people assume cannot work when the process is not attached to a terminal.

Paper replaces vanilla's console reader with its own (`PaperConsole`, which extends Minecrell's
`SimpleTerminalConsole`). That class asks JLine for a system terminal and, when there is not one — which is
exactly the case for a pipe — falls back to reading `System.in` line by line. Each line goes to
`handleConsoleInput`, which puts it on a concurrent queue that the tick thread drains; Folia drains the same
queue on its global region tick. So piped commands arrive, and a command sent before the server is ready is
queued rather than dropped. The only visible difference from a terminal is one warning line saying advanced
terminal features are not available.

Output is read from the process rather than from `logs/latest.log`: nothing is missed before the log file is
opened, a line that a crash prevents from being flushed is still seen, and standard error — where a JVM
prints the reason it refused to start at all — arrives in the same stream in the order it happened. Both
directions are pinned to UTF-8, and the server is started with `-Dfile.encoding`, `-Dstdout.encoding` and
`-Dstderr.encoding` set to match, so that reading it as UTF-8 is correct rather than lucky.

## Building it

```sh
./gradlew build          # tests, then the shaded jar in build/libs
```

Kotlin 2.4.10 on a Java 21 toolchain, Gradle 9.7.1, one shaded jar with SnakeYAML, Clikt and
kotlinx.serialization inside. The tests start a real child JVM that behaves like a server on request —
dying during start-up, ignoring `stop`, printing a stack trace at the wrong moment — because those are the
cases a real server will not perform on cue.

## Credits

The `NOTICE` file lists what mcstage depends on, where each fact about server behaviour was read from, and
the projects that solved a neighbouring problem first. `run-task`, MockBukkit, `mc-runtime-test`,
`docker-minecraft-server` and `mcrcon` are all worth knowing about; none of their code is in here, and
reading them shaped what this does and does not try to do.

Server jars come from PaperMC's fill API and Purpur's API. mcstage is an independent project and is not
affiliated with, endorsed by or sponsored by PaperMC, PurpurMC, Mojang or Microsoft.

## Licence

Apache-2.0. See `LICENSE`.






