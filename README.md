# mcstage

[![build](https://github.com/SalyyS1/mcstage/actions/workflows/build.yml/badge.svg)](https://github.com/SalyyS1/mcstage/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/SalyyS1/mcstage)](https://github.com/SalyyS1/mcstage/releases)

`mcstage` boots real Minecraft servers (`paper`, `folia`, `purpur`), installs your built plugin `.jar`, drives
commands over the server console, asserts on live log lines, quiet windows, crash signatures, and generated plugin files,
and emits human, JSON, JUnit XML, or OASIS SARIF v2.1.0 reports. It downloads official server builds, verifies publisher
digests (`SHA-256` / `MD5`) on both cache write and cache read, and reuses cached jars and worlds across runs:

- **Interactive Browser Tool:** [MCStage Scenario Matrix Builder & Crash Log Simulator](https://salyys1.github.io/mcstage/) (100% client-side YAML builder, offline schema validator, GitHub Actions generator, and `MCS001`-`MCS010` crash log / SARIF v2.1.0 analyzer).

```
$ mcstage test mcstage.yml --accept-eula --sarif build/reports/mcstage.sarif
== paper 1.21.4
   ok   boot
   ok   expect Warps to enable
   ok   expect file "plugins/Warps/config.yml" to exist
   ok   send "warp reload"
   ok   expect contains "reloaded 4 warps"
   ok   expect nothing matches /WARN|ERROR/ for 2.0s
== folia 1.21.4
   ok   boot
   ok   expect Warps to enable
   [MCS003] Folia or Paper thread-safety violation: [03:12:04 ERROR]: Thread failed main thread check
   failure: crash-guard triggered [MCS003] at log line 19: Folia or Paper thread-safety violation

Summary: 1 passed, 1 failed in 48.6s
```

Exit code `1` on scenario failure, exit code `2` on setup/environment error, with instance directories,
`crash-reports/`, and `logs/latest.log` preserved on disk for inspection.

## Why `mcstage` Exists

Testing a Minecraft plugin properly means booting a real server across the platforms and versions you claim
to support:

- **API mocks** (such as MockBukkit) never start a real server, so they cannot catch classloading failures on a new Minecraft version, Folia region-thread violations at runtime, broken `plugin.yml` or `paper-plugin.yml` wiring, or hung shutdown hooks.
- **Build-tool tasks** (such as `run-task`) start a real server well, but belong to a single Gradle layout rather than acting as a standalone multi-platform matrix runner with assertions, crash classification, JUnit XML, and SARIF v2.1.0 output.
- **Container images** target production operators rather than automated plugin CI scenarios.

`mcstage` is a single standalone CLI JAR: no build-tool lock-in, no background daemon, no containers, and no mocks.

## Getting Started

Requires Java 21+ to run `mcstage` (`--java` or `MCSTAGE_JAVA` can point target servers at another JDK if needed).
Download `mcstage-0.3.0.jar` from [Releases](https://github.com/SalyyS1/mcstage/releases):

```sh
java -jar mcstage-0.3.0.jar init                          # generates starter mcstage.yml
java -jar mcstage-0.3.0.jar validate mcstage.yml          # offline schema, regex, matrix, and Java check
java -jar mcstage-0.3.0.jar test mcstage.yml --accept-eula --sarif mcstage.sarif
```

## Writing a Scenario (`mcstage.yml`)

```yaml
name: warps survive a reload
platforms: [paper, folia, purpur]
versions: ["latest", "1.21.4"]
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
  - send: "warp reload"
  - expect: re:reloaded \d+ warps?
  - not-expect: "re:WARN|ERROR"
    timeout: 2s
  - file-not-exists: "plugins/Warps/corrupt.bak"
  - wait: 3s
```

Three platforms across two versions expands to a 6-server matrix run sequentially. Every key, crash rule (`MCS001`-`MCS011`),
and step type (`expect`, `not-expect`, `send`, `wait`, `enabled`, `file-exists`, `file-not-exists`, `file-contains`)
is documented in [`docs/scenarios.md`](docs/scenarios.md).

## CLI Commands

| Command | Description |
| --- | --- |
| `mcstage test <file>` | Run a scenario across every `(platform, version)` pair (`--format console\|json\|junit\|sarif`, `--sarif <path>`). |
| `mcstage validate <file>` | Offline dry-run validation of YAML schema, regex syntax, typo hints, matrix cases, Java runtime, and `--check-plugins` globs. |
| `mcstage run <dir>` | Boot an existing server instance (`--expect` to assert, or `--attach` for interactive console dev loop). |
| `mcstage create <dir>` | Download a verified build and prepare an instance directory without starting it. |
| `mcstage versions [platform]` | List versions currently published by Paper, Folia, and Purpur APIs. |
| `mcstage cache` | Inspect digest-addressed cached server jars (`--clear` to purge). |
| `mcstage init [file]` | Scaffold a starter `mcstage.yml` scenario. |

Interactive local development without hand-maintaining start scripts:

```sh
mcstage create dev --platform paper --version 1.21.4 --accept-eula
mcstage run dev --plugin build/libs/warps-1.4.jar --attach
```

## Crash Diagnostics & Safety by Design

- **Structured Crash & Thread-Violation Classifier (`MCS001`-`MCS011`):** Automatically inspects live server console output and newly written `crash-reports/crash-*.txt` files for plugin load/dependency failures (`MCS001`), `onEnable` or JVM bytecode linkage errors (`MCS002`), Folia region-thread violations (`MCS003`), unhandled event/command exceptions (`MCS004`), Watchdog stalls / `OutOfMemoryError` (`MCS005`), and saved crash reports (`MCS006`). Setting `crash-guard: true` fails the case automatically whenever `MCS001`-`MCS006` triggers.
- **Explicit EULA Agreement:** `mcstage` never sets `-Dcom.mojang.eula.agree=true` behind your back. `eula.txt` is written only when `--accept-eula` is explicitly passed; otherwise setup exits with code `2`.
- **Loopback-Only Binding & Automatic Free-Port Probing:** Test instances bind to `127.0.0.1` with `online-mode=false`, `enable-rcon=false`, and `enable-query=false`. If port `25565` is already occupied on loopback (or `--port 0` is passed), `mcstage` automatically allocates an unused local port so concurrent local servers never collide.
- **Re-Verified Digest Cache (`~/.mcstage/jars`):** Downloaded server jars are stored by publisher digest (`sha256-...` for Paper/Folia, `md5-...` for Purpur) and re-hashed on every cache read as well as download. `--offline` disables all network calls.
- **Confined Filesystem Assertions:** `file-exists`, `file-not-exists`, and `file-contains` steps reject path traversal (`..`) and absolute paths, polling strictly inside the instance directory while continuing to drain live server logs and check `forbid` rules.

## Exit Codes

| Code | Meaning |
| --- | --- |
| `0` | Every `(platform, version)` case passed all steps, `forbid` checks, and `crash-guard` rules. |
| `1` | One or more servers booted and failed a scenario assertion, `forbid` rule, `crash-guard` signature, or clean shutdown. |
| `2` | Setup error before testing (invalid YAML/regex, missing plugin glob, unaccepted EULA, missing Java, or network failure). |

## GitHub Actions Integration (`action.yml` + JUnit XML + SARIF v2.1.0)

Use the composite action `SalyyS1/mcstage@v0.3.0` to emit both JUnit XML test summaries and OASIS SARIF v2.1.0 Code Scanning alerts from a single server run:

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
        uses: SalyyS1/mcstage@v0.3.0
        with:
          scenario: mcstage.yml
          accept-eula: 'true'
          format: junit
          output: build/test-results/mcstage.xml
          sarif: build/reports/mcstage.sarif

      - name: Upload SARIF Diagnostics
        if: always()
        uses: github/codeql-action/upload-sarif@v3
        with:
          sarif_file: build/reports/mcstage.sarif
```

## Related Salyvn Minecraft Toolchain

- [foliascan](https://github.com/SalyyS1/foliascan): Static bytecode analyzer for Paper-to-Folia regionized multithreading safety with SARIF v2.1.0 output.
- [pluginscope](https://github.com/SalyyS1/pluginscope): Deep static inspector for Bukkit/Paper/Folia plugin JARs, permissions, commands, and bytecode risks.
- [serverdoctor](https://github.com/SalyyS1/serverdoctor): Offline auditor and diagnostic analyzer for Minecraft server configurations, crash reports, and timings.
- [minecraftkit](https://github.com/SalyyS1/minecraftkit): MCP server giving AI coding assistants Paper/Folia migration, crash-log, and server tooling.
- [packmerge](https://github.com/SalyyS1/packmerge) / [packmigrate](https://github.com/SalyyS1/packmigrate) / [glyphsmith](https://github.com/SalyyS1/glyphsmith) / [shadersmith](https://github.com/SalyyS1/shadersmith): Resource pack, custom font, item definition, and GLSL shader contract CLIs.

## Building from Source

```sh
./gradlew build --no-daemon
```

Kotlin 2.4.10 on Java 21, Gradle 9.7.1, producing a reproducible shaded JAR in `build/libs/`. Runner tests spawn real child JVMs over piped UTF-8 `stdin`/`stdout` to exercise startup crashes, console commands, negative log windows, file assertions, Folia crash-guard rules, SARIF v2.1.0 generation, and hung shutdowns deterministically.

## Credits & License

See `NOTICE` for upstream API and design references (`run-task`, MockBukkit, `mc-runtime-test`, `docker-minecraft-server`, `mcrcon`). Server builds are fetched from PaperMC Fill API and Purpur API. Independent project; not affiliated with PaperMC, PurpurMC, Mojang, or Microsoft.

Apache-2.0. See `LICENSE`.
