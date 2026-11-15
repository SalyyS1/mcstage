# Scenario files

A scenario is a YAML mapping. It says which servers to run, what to install on them, what to type, what multi-region
workloads to inject, and what must appear (or must not appear) in the console output, barrier timing, or instance
filesystem. `mcstage test <file>` runs it; every platform is tried against every version, one server at a time.
`mcstage validate <file> [--check-plugins]` checks the scenario offline before starting any server.

An unknown key is an error (with a typo suggestion when close to a known key) rather than something quietly
ignored, so a mistyped `timout:` stops immediately instead of doing nothing.

## Top level

| Key | Default | Meaning |
| --- | --- | --- |
| `name` | the file name | What the report calls this scenario. |
| `platforms` / `platform` | `paper` | Any of `paper`, `folia`, `purpur`. A single string is fine. |
| `versions` / `version` | `latest` | Exact versions, a family such as `1.21` or `26.3`, or `latest`. |
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
| `crash-guard` | `false` | Fail the case automatically if any fatal server/plugin crash or barrier signature (`MCS001`-`MCS006`, `MCS012`) is detected in console output or `crash-reports/`. |
| `timeout` | `60s` | The default timeout for a step that does not give its own. |
| `steps` | required | What to do once the server is ready. |

## Matching a line or file content

A string is a substring, matched case-sensitively:

```yaml
- expect: "reloaded 4 warps"
```

A string starting with `re:` is a regular expression, matched anywhere in the line (and validated offline during `mcstage validate`):

```yaml
- expect: re:reloaded \d+ warps
forbid:
  - re:Exception in thread "Server thread"
```

Both forms apply identically to console output (`expect`, `not-expect`, `forbid`, `probe-expect`) and file content (`file-contains`).

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
  - inject-lag:                          # Folia multi-region synthetic lag & merge/split barrier stress
      regions: 3
      mode: merge-split                  # disjoint or merge-split
      spacing: 1024                      # >= 768 blocks (3 Folia 16-chunk sections)
      world: "minecraft:overworld"
      duration: 2s
      cadence: 250ms
  - no-barrier-stall:                    # assert no chunk/region barrier stall or probe RTT > max-stall
      max-stall: 400ms
      probe-command: "list"
    timeout: 2s
  - send: "warp reload"
  - not-expect: "re:WARN|ERROR"          # assert no matching console line appears during the window
    timeout: 2s
  - file-not-exists: "plugins/Warps/corrupt.bak"
  - wait: 5s                             # let the server run, still watching for forbidden lines
```

- `enabled: Warps` waits for Bukkit's plugin manager log line (`Enabling Warps v1.4`), satisfied as `onEnable` runs.
- `send` writes a command to the server's standard input without waiting. Follow it with `expect`, `not-expect`, `file-exists`, or `file-contains`.
- `inject-lag` plans `regions` (`1..64`, default `2`) anchor coordinates spaced by `spacing` blocks (`>= 768`, default `1024`) so each anchor sits at Chebyshev section distance `>= 3` on Folia's `ThreadedRegionizer` lattice. In `disjoint` mode, anchors tick concurrently on separate region threads; in `merge-split` mode (`regions >= 2`), intermediate 256-block bridge tickets are alternated on even/odd pulses to force Folia region merges and splits across tick barriers. Custom `command` / `commands` templates support `{region}`, `{x}`, `{y}`, `{z}`, `{chunkX}`, `{chunkZ}`, `{sectionX}`, `{sectionZ}`, `{world}`, and `{pulse}`. Shorthand `- inject-lag: 2s` uses 2 disjoint regions at 1024-block spacing.
- `no-barrier-stall` inspects all console lines since server boot (or since the previous `no-barrier-stall` step, catching stalls emitted during a preceding `inject-lag` step) plus live output over `window` (`timeout`, default `1s`) for Folia/Moonrise chunk or region barrier deadlocks, synchronous tick-thread chunk loads, and `Running Xms behind` / `Ticking region took Xms` spikes exceeding `max-stall`. When `probe-command` is set, it also dispatches the command on stdin and verifies round-trip console response latency stays within `max-stall`. Shorthand `- no-barrier-stall: 400ms` sets `max-stall: 400ms`.
- `not-expect` drains live server output for `timeout` (`2s` when omitted) and fails immediately if any console line matches.
- `file-exists`, `file-not-exists`, and `file-contains` inspect a relative path confined inside the server instance directory while continuing to drain live server logs and enforce `forbid` rules. Paths containing `..` or absolute roots are rejected at validation time.
- `wait` drains server logs for the given duration so forbidden lines or unexpected exits during background tasks are caught.

## Crash Diagnostics (`MCS001` - `MCS013`) and Reports (`console`, `json`, `junit`, `sarif`)

Every matrix case is inspected by `CrashAnalyzer`, which classifies console output and newly written `crash-reports/crash-*.txt` files into rule IDs:

- `MCS001`: Plugin JAR load or dependency resolution failure (`InvalidPluginException`, `UnknownDependencyException`).
- `MCS002`: Plugin `onEnable` failure or JVM classloader/linkage error (`UnsupportedClassVersionError`, `NoSuchMethodError`, `NoClassDefFoundError`).
- `MCS003`: Folia or Paper off-thread world or entity access violation (`Thread failed main thread check`, `TickThread.ensureTickThread`).
- `MCS004`: Unhandled exception during event dispatch or command execution (`Could not pass event`, `Unhandled exception executing command`).
- `MCS005`: Server Watchdog tick stall dump or `java.lang.OutOfMemoryError`.
- `MCS006`: Fatal crash report saved under `crash-reports/crash-*.txt`.
- `MCS007` - `MCS011`: Boot timeout/exit (`MCS007`), step failure (`MCS008`), `forbid` match (`MCS009`), hung shutdown (`MCS010`), and setup/EULA error (`MCS011`).
- `MCS012`: Folia `ThreadedRegionizer` merge/split barrier stall, Moonrise `ChunkTaskScheduler` / `ChunkHolderManager` deadlock, or `no-barrier-stall` violation (fatal when `crash-guard: true`).
- `MCS013`: Server or Folia regional tick lag spike (`Running Xms behind`, `Ticking region took Xms`) or synchronous chunk load on a tick thread.

`--format` selects `console` (default), `json` (structured CI JSON), `junit` (standard JUnit XML `<testsuites>`), or `sarif` (OASIS SARIF v2.1.0). Passing `--sarif <path>` writes a SARIF v2.1.0 report alongside any primary `--format` in a single server run.

## Aggregating JUnit XML Reports (`mcstage aggregate`)

When CI shards scenarios across multiple runners (or combines unit tests with real-server matrix suites), merge all XML artifacts into a single report:

```sh
mcstage aggregate build/test-results build/mcstage-shards/*.xml \
  --name "warps-ci-matrix" \
  -o build/reports/merged-junit.xml \
  --summary-markdown "$GITHUB_STEP_SUMMARY" \
  --fail-on-failure
```

`mcstage aggregate` accepts both `<testsuites>` and `<testsuite>` root elements, automatically skips its own `-o` output path when scanning a directory, disables XML DOCTYPE/external entities (XXE-safe), and supports `--format junit|json|markdown`.

## Instances and Port Allocation

Each case gets a directory under `.mcstage` next to the scenario file (`--work` moves it) named after the platform and version. Worlds and server jars are reused across runs; `--clean` deletes instance directories first.

Ports start at `--port` (`25565` by default, or `0` for OS ephemeral ports) and automatically advance past any loopback port already bound on the host machine.
