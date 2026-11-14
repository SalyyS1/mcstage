# Scenario files

A scenario is a YAML mapping. It says which servers to run, what to install on them, what to type, and what
must appear in the console output or instance filesystem. `mcstage test <file>` runs it; every platform is
tried against every version, one server at a time. `mcstage validate <file> [--check-plugins]` checks the
scenario offline before starting any server.

An unknown key is an error (with a typo suggestion when close to a known key) rather than something quietly
ignored, so a mistyped `timout:` stops immediately instead of doing nothing.

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

## Matching a line or file content

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

Both forms apply identically to console output (`expect`, `forbid`) and file content (`file-contains`).

## Steps

A step is a mapping with one action, plus an optional `timeout`.

```yaml
steps:
  - enabled: Warps                       # wait for the plugin manager to report Warps enabling
    timeout: 60s
  - file-exists: "plugins/Warps/config.yml"
  - send: "warp set spawn"               # type a command into the console
  - expect: "set warp"                   # wait for a console line
  - file-contains: "plugins/Warps/warps.yml"
    match: "re:spawn:\\s+world"          # wait for a matching line inside an instance file
    timeout: 5s
  - wait: 5s                             # let the server run, still watching for forbidden lines
```

- `enabled: Warps` waits for Bukkit's plugin manager log line (`Enabling Warps v1.4`), satisfied as `onEnable` runs.
- `send` writes a command to the server's standard input without waiting. Follow it with `expect`, `file-exists`, or `file-contains`.
- `file-exists` and `file-contains` poll a relative path confined inside the server instance directory while continuing to drain live server logs and enforce `forbid` rules. Paths containing `..` or absolute roots are rejected at validation time.
- `wait` drains server logs for the given duration so forbidden lines or unexpected exits during background tasks are caught.

## Reports (`console`, `json`, `junit`)

`--format` selects `console` (default), `json` (structured CI JSON), or `junit` (standard JUnit XML `<testsuites>` for GitHub Actions test reporters, GitLab CI, and Jenkins).

## Instances and Port Allocation

Each case gets a directory under `.mcstage` next to the scenario file (`--work` moves it) named after the platform and version. Worlds and server jars are reused across runs; `--clean` deletes instance directories first.

Ports start at `--port` (`25565` by default, or `0` for OS ephemeral ports) and automatically advance past any loopback port already bound on the host machine.

## Full example

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
  - file-exists: "plugins/Warps/config.yml"
  - send: "warp set spawn"
  - expect: "set warp spawn"
  - file-contains: "plugins/Warps/warps.yml"
    match: "spawn"
  - send: "warp reload"
  - expect: re:reloaded \d+ warps?
  - wait: 5s
```

```sh
mcstage validate warps.yml --check-plugins
mcstage test warps.yml --accept-eula --format junit -o build/test-results/mcstage.xml
```
