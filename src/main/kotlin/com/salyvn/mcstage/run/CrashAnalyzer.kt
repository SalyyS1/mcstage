package com.salyvn.mcstage.run

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Metadata for a structured MCStage diagnostic rule, shared between console diagnostics, JSON reports,
 * and OASIS SARIF v2.1.0 Code Scanning exports.
 */
data class DiagnosticRule(
    val id: String,
    val name: String,
    val defaultSeverity: String,
    val shortDescription: String,
    val fullDescription: String,
    val help: String,
    val fatalForCrashGuard: Boolean,
)

/**
 * One concrete diagnostic finding observed during a server matrix case run.
 */
data class DiagnosticFinding(
    val ruleId: String,
    val severity: String,
    val message: String,
    val lineNumber: Int? = null,
    val matchedLine: String? = null,
    val artifactPath: String? = null,
)

/**
 * Classifies server console transcripts and newly written `crash-reports/crash-*.txt` files into
 * actionable Paper, Folia, and Purpur diagnostic findings (`MCS001` through `MCS011`).
 */
object CrashAnalyzer {

    val RULES: List<DiagnosticRule> = listOf(
        DiagnosticRule(
            id = "MCS001",
            name = "PluginLoadFailure",
            defaultSeverity = "error",
            shortDescription = "Plugin JAR failed to load or resolve dependencies",
            fullDescription = "Bukkit, Paper, or Folia failed to load a plugin JAR due to an invalid plugin descriptor, missing dependency, or circular dependency.",
            help = "Verify plugin.yml or paper-plugin.yml syntax, declared api-version, and required server or bootstrap dependencies.",
            fatalForCrashGuard = true,
        ),
        DiagnosticRule(
            id = "MCS002",
            name = "PluginEnableOrLinkageError",
            defaultSeverity = "error",
            shortDescription = "Plugin failed during onEnable or threw a JVM linkage error",
            fullDescription = "A plugin threw an exception while enabling or hit a classfile version, missing class, or binary linkage mismatch against the target server build.",
            help = "Check Java bytecode target compatibility, shaded dependency relocations, and Mojang/Paper API signatures on this Minecraft version.",
            fatalForCrashGuard = true,
        ),
        DiagnosticRule(
            id = "MCS003",
            name = "FoliaRegionThreadViolation",
            defaultSeverity = "error",
            shortDescription = "Folia or Paper off-thread world or entity access violation",
            fullDescription = "The server caught asynchronous or cross-region access to world chunks, block states, or entities outside the owning tick thread.",
            help = "Dispatch region-local mutations via RegionScheduler or EntityScheduler instead of global or async executors.",
            fatalForCrashGuard = true,
        ),
        DiagnosticRule(
            id = "MCS004",
            name = "UnhandledEventOrCommandException",
            defaultSeverity = "warning",
            shortDescription = "Unhandled exception during event dispatch or console command execution",
            fullDescription = "An uncaught exception escaped an event listener, command executor, or background worker thread during the scenario.",
            help = "Inspect the stack trace in the case log tail and guard nullable or version-specific server state inside the listener or command handler.",
            fatalForCrashGuard = true,
        ),
        DiagnosticRule(
            id = "MCS005",
            name = "WatchdogStallOrOutOfMemory",
            defaultSeverity = "error",
            shortDescription = "Server watchdog tick stall or JVM OutOfMemoryError",
            fullDescription = "The Paper or Folia watchdog reported a stalled tick thread dump, or the JVM exhausted heap or metaspace memory.",
            help = "Avoid blocking network or disk I/O on tick threads, or raise the scenario memory setting if heap pressure is expected.",
            fatalForCrashGuard = true,
        ),
        DiagnosticRule(
            id = "MCS006",
            name = "ServerCrashReportSaved",
            defaultSeverity = "error",
            shortDescription = "Minecraft crash report generated in crash-reports/",
            fullDescription = "The server wrote a fatal crash report file under crash-reports/ during the test run.",
            help = "Open the preserved crash report inside the .mcstage instance directory to inspect the crashing thread and system details.",
            fatalForCrashGuard = true,
        ),
        DiagnosticRule(
            id = "MCS007",
            name = "ServerBootFailure",
            defaultSeverity = "error",
            shortDescription = "Server exited prematurely or timed out before reaching ready state",
            fullDescription = "The server process terminated before printing the ready pattern or exceeded ready-timeout.",
            help = "Check Java version compatibility with the target Minecraft version, port availability, and early boot output.",
            fatalForCrashGuard = false,
        ),
        DiagnosticRule(
            id = "MCS008",
            name = "StepAssertionFailed",
            defaultSeverity = "error",
            shortDescription = "Scenario step assertion failed or timed out",
            fullDescription = "A step in the scenario (expect, not-expect, enabled, file-exists, file-contains, file-not-exists, send, or wait) did not succeed.",
            help = "Compare the expected log pattern or instance file path against the server log tail.",
            fatalForCrashGuard = false,
        ),
        DiagnosticRule(
            id = "MCS009",
            name = "ForbiddenLogMatched",
            defaultSeverity = "error",
            shortDescription = "Server console output matched a scenario forbid pattern",
            fullDescription = "A line printed by the server matched one of the patterns listed under forbid in the scenario file.",
            help = "Fix the plugin error that produced the forbidden log line or refine the forbid matcher if the message is benign.",
            fatalForCrashGuard = false,
        ),
        DiagnosticRule(
            id = "MCS010",
            name = "HungServerShutdown",
            defaultSeverity = "error",
            shortDescription = "Server ignored stop command and had to be forcibly killed",
            fullDescription = "The server remained alive past stop-timeout after receiving the stop command and had to be terminated.",
            help = "Ensure plugin onDisable hooks shut down custom thread pools, database pools, and network sockets promptly.",
            fatalForCrashGuard = false,
        ),
        DiagnosticRule(
            id = "MCS011",
            name = "ScenarioSetupError",
            defaultSeverity = "error",
            shortDescription = "Scenario instance setup or EULA check failed before server launch",
            fullDescription = "MCStage could not prepare or launch the server instance (for example unaccepted EULA, missing offline cached JAR, or invalid runtime).",
            help = "Pass --accept-eula, verify network or cache availability, and run mcstage validate before executing tests.",
            fatalForCrashGuard = false,
        ),
        DiagnosticRule(
            id = "MCS012",
            name = "ChunkOrRegionBarrierStall",
            defaultSeverity = "error",
            shortDescription = "Folia region merge/split or Moonrise chunk barrier stall",
            fullDescription = "The server reported a stalled ThreadedRegionizer merge/split barrier, Moonrise chunk-task scheduler deadlock, or chunk barrier timeout.",
            help = "Eliminate synchronous getChunkAt calls and cross-region CompletableFuture.join() waits on tick threads; use getChunkAtAsync and RegionScheduler.",
            fatalForCrashGuard = true,
        ),
        DiagnosticRule(
            id = "MCS013",
            name = "RegionTickLagSpike",
            defaultSeverity = "warning",
            shortDescription = "Server or Folia region tick lag spike or synchronous chunk load",
            fullDescription = "A server or regional tick loop fell behind schedule or executed a synchronous chunk load on a tick thread.",
            help = "Inspect Spark or JFR tick profiles, replace synchronous chunk loads with async chunk tickets, and verify threshold budgets with no-barrier-stall.",
            fatalForCrashGuard = false,
        ),
        DiagnosticRule(
            id = "MCS014",
            name = "HeapUsageExceeded",
            defaultSeverity = "warning",
            shortDescription = "JVM heap usage exceeded the assert-heap threshold",
            fullDescription = "GC log output showed peak heap usage above the threshold configured in the assert-heap scenario step. This often indicates a memory leak in plugin code or an undersized -Xmx budget.",
            help = "Profile heap with Spark /sparkm heap or VisualVM, check for plugin entity/cache leaks, and increase -Xmx if usage is expected.",
            fatalForCrashGuard = false,
        ),
        DiagnosticRule(
            id = "MCS015",
            name = "FoliaRegionalCrashLoop",
            defaultSeverity = "error",
            shortDescription = "Folia regional tick crash-loop or unrecovered region fault",
            fullDescription = "A Folia region thread repeatedly threw unhandled tick exceptions within a short window or failed to recover regional ticking within max-recovery.",
            help = "Guard per-region entity/block tick callbacks, register retired callbacks on EntityScheduler, and isolate faulty chunks instead of re-queueing failing tasks on every tick.",
            fatalForCrashGuard = true,
        ),
        DiagnosticRule(
            id = "MCS016",
            name = "RconLatencyBudgetExceeded",
            defaultSeverity = "warning",
            shortDescription = "RCON command round-trip or p95 latency exceeded budget",
            fullDescription = "Source RCON command execution timed out or exceeded the configured max-p95 latency threshold under simulated or live network conditions.",
            help = "Offload blocking plugin commands from main/region threads, tune RCON socket read timeouts, and avoid stalling RconThread command dispatch.",
            fatalForCrashGuard = false,
        ),
        DiagnosticRule(
            id = "MCS017",
            name = "RconPacketLossOrFrameDesync",
            defaultSeverity = "error",
            shortDescription = "RCON packet loss exhausted retries or wire frame/auth desynchronized",
            fullDescription = "An RCON session exhausted configured retries due to packet drops, encountered malformed/truncated binary frames, or lost request-ID/authentication synchronization.",
            help = "Reconnect and re-authenticate RCON sockets on EOF or negative requestId (-1), enforce 4096-byte client payload bounds, and validate response request IDs.",
            fatalForCrashGuard = true,
        ),
        DiagnosticRule(
            id = "MCS018",
            name = "BotProtocolProbeFailed",
            defaultSeverity = "error",
            shortDescription = "Headless Minecraft SLP protocol handshake failed or exceeded latency budget",
            fullDescription = "The loopback server did not respond to a Minecraft Java Edition Server List Ping handshake, returned an unexpected version/MOTD, or exceeded max-latency.",
            help = "Verify Netty listener binding, server-port allocation, ViaVersion/packet listener hooks, and main-thread handshake stalls.",
            fatalForCrashGuard = true,
        ),
        DiagnosticRule(
            id = "MCS019",
            name = "BridgeTelemetryBreach",
            defaultSeverity = "error",
            shortDescription = "Companion mckit-bridge-plugin reported TPS/MSPT degradation or SEVERE log errors",
            fullDescription = "The assert-bridge step observed TPS below min-tps, MSPT above max-mspt, or SEVERE errors exceeding max-errors on the companion mckit-bridge-plugin loopback API.",
            help = "Inspect plugins/MCKitBridge telemetry and /api/v1/errors output to isolate plugin tick regressions or uncaught exceptions.",
            fatalForCrashGuard = true,
        ),
        DiagnosticRule(
            id = "MCS020",
            name = "PerformanceRegressionDetected",
            defaultSeverity = "error",
            shortDescription = "Scenario outcome, elapsed duration, or diagnostic set regressed against baseline report",
            fullDescription = "mcstage compare detected an outcome regression (passed -> failed/errored), an elapsed-time slowdown exceeding max-slowdown-pct, or newly introduced MCS diagnostic rules compared to the baseline JSON report.",
            help = "Inspect the markdown or JSON diff from mcstage compare, profile newly added startup/tick work, and resolve new MCS findings before releasing.",
            fatalForCrashGuard = true,
        ),
        DiagnosticRule(
            id = "MCS021",
            name = "WorldIntegrityViolation",
            defaultSeverity = "error",
            shortDescription = "Corrupted level.dat, stale session.lock, or truncated/out-of-bounds Anvil .mca region file",
            fullDescription = "WorldIntegrityChecker detected a missing or non-GZIP level.dat, an unaligned or truncated Anvil .mca region header (< 8192 B or non-4096-byte sectors), out-of-bounds chunk sector pointers, or a stale session.lock.",
            help = "Inspect plugin chunk save hooks, async world mutations, and Paper/Folia shutdown handlers to prevent truncated region writes.",
            fatalForCrashGuard = true,
        ),
    )

    private val RULES_BY_ID: Map<String, DiagnosticRule> = RULES.associateBy { it.id }

    private data class LogSignature(
        val ruleId: String,
        val regex: Regex,
        val summary: (String) -> String,
    )

    private val LOG_SIGNATURES: List<LogSignature> = listOf(
        LogSignature(
            ruleId = "MCS001",
            regex = Regex(
                """Could not load 'plugins/[^']+'|org\.bukkit\.plugin\.(InvalidPluginException|InvalidDescriptionException|UnknownDependencyException|CircularPluginDependencyException)""",
            ),
            summary = { line -> "Plugin load or dependency error: ${line.trim()}" },
        ),
        LogSignature(
            ruleId = "MCS002",
            regex = Regex(
                """Error occurred while enabling [^\n]+|java\.lang\.(UnsupportedClassVersionError|NoClassDefFoundError|NoSuchMethodError|NoSuchFieldError|LinkageError|ClassNotFoundException)\b""",
            ),
            summary = { line -> "Plugin enable or JVM linkage failure: ${line.trim()}" },
        ),
        LogSignature(
            ruleId = "MCS003",
            regex = Regex(
                """Thread failed main thread check|Cannot (read|modify) world asynchronously|TickThread\.ensureTickThread|AsyncCatcher|off-main-thread""",
                RegexOption.IGNORE_CASE,
            ),
            summary = { line -> "Folia or Paper thread-safety violation: ${line.trim()}" },
        ),
        LogSignature(
            ruleId = "MCS005",
            regex = Regex(
                """DO NOT REPORT THIS TO PAPER - THIS IS NOT A BUG OR A CRASH|The server has stopped responding!|WatchdogThread|java\.lang\.OutOfMemoryError\b""",
            ),
            summary = { line -> "Watchdog tick stall or JVM OutOfMemoryError: ${line.trim()}" },
        ),
        LogSignature(
            ruleId = "MCS006",
            regex = Regex("""This crash report has been saved to:\s*(.+)"""),
            summary = { line -> "Server saved crash report: ${line.trim()}" },
        ),
        LogSignature(
            ruleId = "MCS004",
            regex = Regex(
                """Could not pass event \S+ to [^\n]+|Unhandled exception executing command [^\n]+|Exception in thread "[^"]+"""",
            ),
            summary = { line -> "Unhandled server exception: ${line.trim()}" },
        ),
        LogSignature(
            ruleId = "MCS012",
            regex = BarrierStallAnalyzer.HARD_BARRIER_REGEX,
            summary = { line -> "Chunk or region barrier stall: ${line.trim()}" },
        ),
        LogSignature(
            ruleId = "MCS013",
            regex = Regex(
                """Can't keep up!\s*Is the server overloaded\?\s*Running\s+\d+(?:\.\d+)?ms\s+or\s+\d+\s+ticks?\s+behind|Loaded chunk\s*\(-?\d+,\s*-?\d+\)\s*synchronously|\bsync-chunk-load\b|Ticking region[^\n]*?took\s+\d+(?:\.\d+)?ms""",
                RegexOption.IGNORE_CASE,
            ),
            summary = { line -> "Region tick lag spike or sync chunk load: ${line.trim()}" },
        ),
        LogSignature(
            ruleId = "MCS015",
            regex = FoliaCrashRecoveryAnalyzer.HARD_CRASH_LOOP_REGEX,
            summary = { line -> "Folia regional crash-loop: ${line.trim()}" },
        ),
        LogSignature(
            ruleId = "MCS016",
            regex = Regex(
                """RCON\s+Client[^\n]*?\btimed\s+out\b|RCON\s+(?:command\s+)?latency[^\n]*?\bexceeded\b""",
                RegexOption.IGNORE_CASE,
            ),
            summary = { line -> "RCON latency spike or client timeout: ${line.trim()}" },
        ),
        LogSignature(
            ruleId = "MCS017",
            regex = Regex(
                """Exception\s+in\s+RCON\b|RconThread[^\n]*?\b(?:terminated|crashed|corrupt|malformed|desync)\b|Corrupt\s+RCON\s+frame\b""",
                RegexOption.IGNORE_CASE,
            ),
            summary = { line -> "RCON wire frame or listener fault: ${line.trim()}" },
        ),
    )

    private const val MAX_FINDINGS_PER_RULE = 5

    fun rule(id: String): DiagnosticRule = RULES_BY_ID.getValue(id)

    fun snapshotCrashReports(instanceDir: Path?): Set<Path> {
        if (instanceDir == null) return emptySet()
        val crashDir = instanceDir.resolve("crash-reports")
        if (!Files.isDirectory(crashDir)) return emptySet()
        return Files.list(crashDir).use { stream ->
            stream.filter { it.isRegularFile() }.map { it.toAbsolutePath().normalize() }.toList().toSet()
        }
    }

    /**
     * Scans live console lines and any newly generated `crash-reports/` files in `instanceDir`.
     */
    fun analyze(
        transcript: List<LogLine>,
        instanceDir: Path? = null,
        preExistingCrashReports: Set<Path> = emptySet(),
    ): List<DiagnosticFinding> {
        val findings = mutableListOf<DiagnosticFinding>()
        val perRuleCount = mutableMapOf<String, Int>()

        for (line in transcript) {
            for (sig in LOG_SIGNATURES) {
                val count = perRuleCount.getOrDefault(sig.ruleId, 0)
                if (count >= MAX_FINDINGS_PER_RULE) continue
                if (sig.regex.containsMatchIn(line.text)) {
                    val rule = rule(sig.ruleId)
                    findings += DiagnosticFinding(
                        ruleId = rule.id,
                        severity = rule.defaultSeverity,
                        message = sig.summary(line.text),
                        lineNumber = line.number,
                        matchedLine = line.text.trim(),
                    )
                    perRuleCount[sig.ruleId] = count + 1
                    break
                }
            }
        }

        if (perRuleCount.getOrDefault("MCS015", 0) == 0) {
            FoliaCrashRecoveryAnalyzer.detectPassiveCrashLoop(transcript)?.let { loopViolation ->
                findings += DiagnosticFinding(
                    ruleId = "MCS015",
                    severity = "error",
                    message = loopViolation.message,
                    lineNumber = loopViolation.lineNumber,
                    matchedLine = loopViolation.matchedLine,
                )
                perRuleCount["MCS015"] = 1
            }
        }

        if (instanceDir != null) {
            val crashDir = instanceDir.resolve("crash-reports")
            if (Files.isDirectory(crashDir)) {
                val newReports = Files.list(crashDir).use { stream ->
                    stream
                        .filter { it.isRegularFile() && it.toAbsolutePath().normalize() !in preExistingCrashReports }
                        .sorted()
                        .toList()
                }
                for (reportFile in newReports) {
                    val summary = summarizeCrashReportFile(reportFile)
                    val relPath = "crash-reports/${reportFile.name}"
                    val alreadyLogged = findings.any {
                        it.ruleId == "MCS006" && (it.matchedLine?.contains(reportFile.name) == true || it.artifactPath == relPath)
                    }
                    if (!alreadyLogged) {
                        findings += DiagnosticFinding(
                            ruleId = "MCS006",
                            severity = "error",
                            message = "Crash report written to $relPath: $summary",
                            artifactPath = relPath,
                        )
                    } else {
                        val idx = findings.indexOfFirst { it.ruleId == "MCS006" && it.artifactPath == null }
                        if (idx >= 0) {
                            val prev = findings[idx]
                            findings[idx] = prev.copy(
                                message = "${prev.message} ($summary)",
                                artifactPath = relPath,
                            )
                        }
                    }
                }
            }
        }

        return findings
    }

    fun firstCrashGuardFailure(findings: List<DiagnosticFinding>): String? {
        val fatal = findings.firstOrNull { RULES_BY_ID[it.ruleId]?.fatalForCrashGuard == true } ?: return null
        val lineRef = fatal.lineNumber?.let { " at log line $it" }.orEmpty()
        return "crash-guard triggered [${fatal.ruleId}]$lineRef: ${fatal.message}"
    }

    private fun summarizeCrashReportFile(file: Path): String {
        val lines = try {
            Files.readAllLines(file, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            return file.name
        }
        val description = lines.firstOrNull { it.trim().startsWith("Description:") }
            ?.substringAfter("Description:")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val exceptionLine = lines.firstOrNull {
            val trimmed = it.trim()
            !trimmed.startsWith("at ") && Regex("""^[A-Za-z0-9_.$]+(Exception|Error)(:.*)?$""").matches(trimmed)
        }?.trim()
        return listOfNotNull(description, exceptionLine).joinToString(" - ").ifEmpty { file.name }
    }
}
