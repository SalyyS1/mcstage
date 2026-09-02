# Scenario files

A scenario is a YAML mapping. It says which servers to run, what to install on them, what to type, and what
must or must not appear in the output. `mcstage test <file>` runs it; every platform is tried against every
version, one server at a time.

An unknown key is an error rather than something quietly ignored, so a mistyped `timout:` stops the run
instead of doing nothing.

## Top level

| Key | Default | Meaning |
| --- | --- | --- |
| `name` | the file name | What the report calls this scenario. |
| `platforms` / `platform` | `paper` | Any of `paper`, `folia`, `purpur`. A single string is fine. |
| `versions` / `version` | `latest` | Exact versions, a family such as `1.21`, or `latest`. |
| `allow-prerelease` | `false` | Let `latest` and a family resolve to a release candidate. |
| `memory` | `2G` | Used for both `-Xms` and `-Xmx`. |
| `java` | found | The java binary or JDK home to run the server with. `--java` overrides it. |
| `jvm-args` | none | Extra JVM arguments, added after the heap and encoding ones. |
| `plugins` / `plugin` | none | Jars to install: a file, a directory of jars, or a glob such as `build/libs/*.jar`. |
| `properties` | none | Entries written into `server.properties`. |
| `ready` | `re:Done \([0-9.,]+s\)!` | The line that means the server is accepting connections. |
| `ready-timeout` | `180s` | How long to wait for it. First boots generate a world; be generous. |
| `stop` | `stop` | The console command that shuts the server down. |
| `stop-timeout` | `60s` | How long to wait after asking, before killing it and reporting that. |
| `forbid` | none | Lines that must not appear, checked against every line for the whole run. |
| `timeout` | `60s` | The default timeout for a step that does not give its own. |
| `steps` | required | What to do once the server is ready. |

## Matching a line

A string is a substring, matched case-sensitively:

```yaml
- expect: "reloaded 4 warps"
```

A string starting with `re:` is a regular expression, matched anywhere in the line:

```yaml
- expect: re:reloaded \d+ warps
forbid:
  - re:Exception in thread "Server thread"
```

There is nothing in between. Guessing which one was meant produces a matcher that works until the day a
plugin is called `C++`.

Both forms are applied to the server's output as it arrives, standard error included.

## Steps

A step is a mapping with exactly one action, plus an optional `timeout`.

```yaml
steps:
  - enabled: Warps          # wait for the plugin manager to report Warps enabling
    timeout: 60s
  - send: "warp set spawn"  # type a command into the console
  - expect: "set warp"      # wait for a line
  - wait: 5s                # let the server run, still watching for forbidden lines
```

`enabled: Warps` waits for the line Bukkit's plugin manager logs through the plugin's own logger as it
enables it — `Enabling Warps v1.4` — so it is satisfied at the moment `onEnable` is reached, before anything
the plugin itself prints. `NOTICE` cites where that message comes from.

`send` does not wait for anything. Follow it with an `expect` when the point is what it printed.

`wait` is not padding: forbidden patterns are checked while it runs, so it is how you say "and nothing blew
up in the next five seconds".

A step that times out fails the case and the report says what it was waiting for. So does the server dying
at any point, and so does anything in `forbid` appearing, even when every step was satisfied.

## What is reported but never fails a run

Every case reports the build it resolved to, the jar and its digest, the Java it used, how long it took to
become ready, how many lines it printed, how many carried a warning or error level, how many looked like a
thrown exception, and where the instance directory is.

Those counts are facts, not verdicts. A stock Paper with no plugins prints four warnings and one error on a
normal boot, so a harness that failed on either would fail on a clean server. If one of them matters to you,
put it in `forbid`, where it is visible and yours.

## Instances

Each case gets a directory under `.mcstage` next to the scenario file — `--work` moves it — named after the
platform and version. It is reused between runs, so the world and therefore the second boot are faster, and
when something fails the directory is still there with the server's own `logs/latest.log` in it. `--clean`
deletes them first.

Ports are allocated from `--port` (25565 by default) upwards, one per case.

## A full example

```yaml
name: warps survive a reload on everything I support
platforms: [paper, folia, purpur]
versions: ["latest", "1.21.4"]
memory: 2G

plugins:
  - build/libs/*.jar

properties:
  level-type: minecraft:flat

forbid:
  - "Could not load 'plugins"
  - "Error occurred while enabling"
  - re:Exception in thread "Server thread"

ready-timeout: 240s
timeout: 20s

steps:
  - enabled: Warps
  - send: "warp set spawn"
  - expect: "set warp spawn"
  - send: "warp reload"
  - expect: re:reloaded \d+ warps?
  - wait: 5s
```

Six servers, one command, and an exit code that means something:

```sh
mcstage test warps.yml --accept-eula --format json -o warps-report.json
```
