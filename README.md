# mcstage

[![build](https://github.com/SalyyS1/mcstage/actions/workflows/build.yml/badge.svg)](https://github.com/SalyyS1/mcstage/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/SalyyS1/mcstage)](https://github.com/SalyyS1/mcstage/releases)

`mcstage` boots real Minecraft servers (`paper`, `folia`, `purpur`), installs your built plugin `.jar`, drives
commands, Folia multi-region synthetic lag workloads, and deterministic Source RCON fault profiles, asserts on live
log lines, Folia regional crash-loop recovery (`assert-region-recovery`), chunk/region barrier stall budgets, JVM heap
thresholds (`assert-heap`), quiet windows, crash signatures, and generated plugin files, and emits human, JSON, JUnit
XML, Markdown CI summaries, or OASIS SARIF v2.1.0 reports. It downloads official server builds, verifies publisher
digests (`SHA-256` / `MD5`) on both cache write and cache read, and reuses cached jars and worlds across runs:

- **Interactive Browser Tool:** [MCStage Scenario Matrix Builder, Folia Recovery, RCON Fault & JUnit Aggregator Lab](https://salyys1.github.io/mcstage/) (100% client-side YAML builder, Folia `ThreadedRegionizer` lattice & crash-loop recovery planner, deterministic SplitMix64 RCON latency/drop simulator, `MCS001`-`MCS017` crash/barrier log analyzer, and multi-file JUnit XML aggregator).

```
$ mcstage test mcstage.yml --accept-eula --sarif build/reports/mcstage.sarif
== paper 26.3
   ok   boot
   ok   expect Warps to enable
   ok   expect file "plugins/Warps/config.yml" to exist
   ok   inject merge-split lag across 3 region(s) for 2s
   ok   expect no chunk/region barrier stall > 350ms for 1s
== folia 26.3
   ok   boot
   ok   expect Warps to enable
   ok   inject merge-split lag across 3 region(s) for 2s
   FAIL expect no chunk/region barrier stall > 350ms for 1s
   [MCS012] Chunk or region barrier stall: [ThreadedRegionizer] Region merge barrier stalled waiting on chunk (48, 0)

Summary: 1 passed, 1 failed in 51.4s
```

Exit code `1` on scenario failure, exit code `2` on setup/environment error, with instance directories,
`crash-reports/`, and `logs/latest.log` preserved on disk for inspection.

## Why `mcstage` Exists

Testing a Minecraft plugin properly means booting a real server across the platforms and versions you claim
to support:

- **API mocks** (such as MockBukkit) never start a real server, so they cannot catch classloading failures on a new Minecraft version, Folia `ThreadedRegionizer` merge/split barrier deadlocks, region-thread violations at runtime, broken `plugin.yml` or `paper-plugin.yml` wiring, or hung shutdown hooks.
- **Build-tool tasks** (such as `run-task`) start a real server well, but belong to a single Gradle layout rather than acting as a standalone multi-platform matrix runner with multi-region stress injection, barrier stall budgets, crash classification, JUnit XML aggregation, and SARIF v2.1.0 output.
- **Container images** target production operators rather than automated plugin CI scenarios.

`mcstage` is a single standalone CLI JAR: no build-tool lock-in, no background daemon, no containers, and no mocks.

## Getting Started

Requires Java 21+ to run `mcstage` (`--java` or `MCSTAGE_JAVA` can point target servers at another JDK if needed).
Download `mcstage-0.6.0.jar` from [Releases](https://github.com/SalyyS1/mcstage/releases):

```sh
java -jar mcstage-0.6.0.jar init                          # generates starter mcstage.yml
java -jar mcstage-0.6.0.jar validate mcstage.yml          # offline schema, Folia spacing, RCON fault, regex, matrix, and Java check
java -jar mcstage-0.6.0.jar test mcstage.yml --accept-eula --format junit -o build/reports/mcstage.xml --sarif mcstage.sarif
java -jar mcstage-0.6.0.jar aggregate build/reports -o build/reports/merged.xml --summary-markdown summary.md --fail-on-failure
```

## Writing a Scenario (`mcstage.yml`)

```yaml
name: warps survive Folia region merge-split and reload
platforms: [paper, folia, purpur]
versions: ["latest", "26.3", "1.21.4"]
memory: 2G
crash-guard: true

plugins:
  - build/libs/*.jar

forbid:
  - "Could not load 'plugins"
  - re:Exception in thread "Server thread"

timeout: 20s

steps:
  - enabled: Warps
  - file-exists: "plugins/Warps/config.yml"
  - send: "warp set spawn"
  - expect: "set warp spawn"
  - file-contains: "plugins/Warps/warps.yml"
    match: "spawn"
  - inject-lag:
      regions: 3
      mode: merge-split
      spacing: 1024
      duration: 2s
      cadence: 250ms
  - no-barrier-stall:
      max-stall: 400ms
      probe-command: "list"
    timeout: 2s
  - assert-region-recovery:
      max-faults: 1
      loop-window: 5s
      max-recovery: 1500ms
      window: 2s
  - rcon-fault:
      commands: ["list", "tps"]
      iterations: 6
      latency: 25ms
      jitter: 15ms
      drop-rate: 20%
      max-retries: 4
      max-p95: 350ms
      seed: 42
  - send: "warp reload"
  - expect: re:reloaded \d+ warps?
  - not-expect: "re:WARN|ERROR"
    timeout: 2s
  - file-not-exists: "plugins/Warps/corrupt.bak"
```

Three platforms across three versions expands to a 9-server matrix run sequentially. Every key, crash rule (`MCS001`-`MCS017`),
and step type (`expect`, `not-expect`, `send`, `wait`, `enabled`, `file-exists`, `file-not-exists`, `file-contains`,
`inject-lag`, `no-barrier-stall`, `assert-heap`, `assert-region-recovery`, `rcon-fault`) is documented in [`docs/scenarios.md`](docs/scenarios.md).

## CLI Commands

| Command | Description |
| --- | --- |
| `mcstage test <file>` | Run a scenario across every `(platform, version)` pair (`--format console\|json\|junit\|sarif`, `--sarif <path>`). |
| `mcstage validate <file>` | Offline dry-run validation of YAML schema, Folia region spacing invariants, regex syntax, matrix cases, Java runtime, and `--check-plugins` globs. |
| `mcstage aggregate <inputs>...` | Merge multiple JUnit XML reports (files, directories, or globs) into a single `<testsuites>` XML, JSON, or GitHub Flavored Markdown summary (`--summary-markdown`, `--fail-on-failure`). |
| `mcstage run <dir>` | Boot an existing server instance (`--expect` to assert, or `--attach` for interactive console dev loop). |
| `mcstage create <dir>` | Download a verified build and prepare an instance directory without starting it. |
| `mcstage versions [platform]` | List versions currently published by Paper, Folia, and Purpur APIs. |
| `mcstage cache` | Inspect digest-addressed cached server jars (`--clear` to purge). |
| `mcstage init [file]` | Scaffold a starter `mcstage.yml` scenario. |

Interactive local development without hand-maintaining start scripts:

```sh
mcstage create dev --platform paper --version 26.3 --accept-eula
mcstage run dev --plugin build/libs/warps-1.4.jar --attach
```

## Folia Multi-Region Lag Injection, Barrier Assertions & Safety by Design

- **Folia `ThreadedRegionizer` Multi-Region Synthetic Lag Injection (`inject-lag`):** Places `N` anchor coordinates on a 2D lattice spaced at least `768` blocks (`3` Folia 16-chunk sections, respecting `emptySectionCreateRadius = 1` + `regionSectionMergeRadius = 1`) so anchors tick on guaranteed disjoint region threads in `disjoint` mode, or alternates minimal spanning-tree 256-block stepping-stone tickets in `merge-split` mode to force deterministic region merge and split barriers.
- **Chunk & Region Barrier Stall Assertions (`no-barrier-stall`):** Enforces millisecond stall budgets (`max-stall`) across Moonrise `ChunkTaskScheduler` / Folia `ThreadedRegionizer` barrier signatures, synchronous tick-thread chunk loads, `Running Xms behind` spikes, and optional active console barrier round-trip probes (`probe-command`).
- **Folia Regional Crash-Loop & Recovery Assertions (`assert-region-recovery` / `MCS015`):** Maps chunk and section fault coordinates onto Folia's 16-chunk `ThreadedRegionizer` sections, enforces sliding-window per-region fault caps (`max-faults` over `loop-window`), and verifies post-fault regional recovery within `max-recovery`.
- **Deterministic Source RCON Fault Injection (`rcon-fault` / `MCS016`-`MCS017`):** Speaks real little-endian binary Source RCON wire packets (`SERVERDATA_AUTH` / `SERVERDATA_EXECCOMMAND`) over loopback TCP sockets while injecting reproducible `SplitMix64`-seeded latency, jitter, packet drops, and corrupt wire frames to verify retry recovery (`MCS017`) and `max-p95` latency budgets (`MCS016`).
- **Structured Crash & Barrier Classifier (`MCS001`-`MCS017`):** Inspects live server console output and newly written `crash-reports/crash-*.txt` files for plugin load/dependency failures (`MCS001`), `onEnable` or JVM bytecode linkage errors (`MCS002`), Folia region-thread violations (`MCS003`), unhandled event/command exceptions (`MCS004`), Watchdog stalls / `OutOfMemoryError` (`MCS005`), saved crash reports (`MCS006`), Folia/Moonrise chunk or region barrier deadlocks (`MCS012`), tick lag spikes (`MCS013`), JVM heap peak breaches (`MCS014`), Folia regional crash loops (`MCS015`), RCON latency budget breaches (`MCS016`), and RCON packet-loss/frame-desync failures (`MCS017`). Setting `crash-guard: true` fails the case automatically whenever `MCS001`-`MCS006`, `MCS012`, `MCS015`, or `MCS017` triggers.
- **XXE-Hardened JUnit XML Aggregation (`mcstage aggregate`):** Consolidates sharded matrix `<testsuites>` and Gradle/Surefire `<testsuite>` XML reports across files, directories, and globs while automatically excluding the `-o` destination file and disabling external XML entities.
- **Explicit EULA Agreement & Loopback Isolation:** `mcstage` never sets `-Dcom.mojang.eula.agree=true` behind your back (`--accept-eula` is required), binds test servers strictly to `127.0.0.1` with automatic free-port probing, re-verifies `SHA-256` / `MD5` digests on every cache read, and confines filesystem assertions strictly inside the instance directory.

## Exit Codes

| Code | Meaning |
| --- | --- |
| `0` | Every `(platform, version)` case (or aggregated JUnit suite) passed all assertions, `forbid` checks, and `crash-guard` rules. |
| `1` | One or more servers failed a scenario assertion, `no-barrier-stall` budget, `forbid` rule, `crash-guard` signature, clean shutdown, or `aggregate --fail-on-failure`. |
| `2` | Setup error before testing (invalid YAML/XML/regex, invalid Folia region spacing, missing plugin glob, unaccepted EULA, missing Java, or network failure). |

## GitHub Actions Integration (`action.yml` + JUnit XML Aggregation + SARIF v2.1.0)

Use the composite action `SalyyS1/mcstage@v0.6.0` to emit JUnit XML test reports, `$GITHUB_STEP_SUMMARY` Markdown tables, and OASIS SARIF v2.1.0 Code Scanning alerts from a single server run:

```yaml
name: integration
on: [push, pull_request]

permissions:
  contents: read
  security-events: write

jobs:
  mcstage:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
      - run: ./gradlew build --no-daemon

      - name: Run Real-Server Matrix
        uses: SalyyS1/mcstage@v0.6.0
        with:
          scenario: mcstage.yml
          accept-eula: 'true'
          format: junit
          output: build/test-results/mcstage.xml
          summary-markdown: ${{ env.GITHUB_STEP_SUMMARY }}
          sarif: build/reports/mcstage.sarif

      - name: Upload SARIF Diagnostics
        if: always()
        uses: github/codeql-action/upload-sarif@v3
        with:
          sarif_file: build/reports/mcstage.sarif
```

## Related Salyvn Minecraft Toolchain

- [foliascan](https://github.com/SalyyS1/foliascan): Static bytecode analyzer for Paper-to-Folia regionized multithreading safety with SARIF v2.1.0 output.
- [pluginscope](https://github.com/SalyyS1/pluginscope): Deep static inspector for Bukkit/Paper/Folia plugin JARs, cross-plugin ASM dataflow leaks, and `paper-plugin.yml` dependency graphs.
- [serverdoctor](https://github.com/SalyyS1/serverdoctor): Offline auditor and diagnostic analyzer for Spark flamegraphs, Folia region stalls, and Paper 26.3 configurations.
- [minecraftkit](https://github.com/SalyyS1/minecraftkit): MCP server giving AI coding assistants Paper/Folia migration, crash-log, and server tooling.
- [packmerge](https://github.com/SalyyS1/packmerge) / [packmigrate](https://github.com/SalyyS1/packmigrate) / [glyphsmith](https://github.com/SalyyS1/glyphsmith) / [shadersmith](https://github.com/SalyyS1/shadersmith): Resource pack, custom font, item definition, and GLSL shader contract CLIs.

## Building from Source

```sh
./gradlew build --no-daemon
```

Kotlin 2.4.10 on Java 21, Gradle 9.7.1, producing a reproducible shaded JAR in `build/libs/`. Runner tests spawn real child JVMs over piped UTF-8 `stdin`/`stdout` to exercise startup crashes, console commands, Folia multi-region lag injection, active/passive barrier stall budgets, negative log windows, file assertions, SARIF v2.1.0 generation, JUnit XML aggregation, and hung shutdowns deterministically.

## Credits & License

See `NOTICE` for upstream API and design references (`run-task`, MockBukkit, `mc-runtime-test`, `docker-minecraft-server`, `mcrcon`). Server builds are fetched from PaperMC Fill API and Purpur API. Independent project; not affiliated with PaperMC, PurpurMC, Mojang, or Microsoft.

Apache-2.0. See `LICENSE`.
