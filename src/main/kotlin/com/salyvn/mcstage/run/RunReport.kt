package com.salyvn.mcstage.run

import java.time.Duration
import java.time.Instant

enum class Outcome {
    /** Every step was satisfied and nothing forbidden appeared. */
    PASSED,

    /** The server ran and disagreed with the scenario: a step timed out, or it died, or it said
     *  something the scenario forbade. */
    FAILED,

    /** No verdict was reached, because something before the test got in the way. */
    ERROR,
}

data class StepResult(
    val describe: String,
    val ok: Boolean,
    val elapsed: Duration,
    val detail: String? = null,
    /** The line that satisfied the step, kept so a report can show what it matched on. */
    val matched: String? = null,
)

/** One platform and version, run start to finish. */
data class CaseResult(
    val case: Scenario.Case,
    val outcome: Outcome,
    val elapsed: Duration,
    val steps: List<StepResult> = emptyList(),
    val failure: String? = null,
    val facts: Map<String, String> = emptyMap(),
    /** The end of the server's output, for a failure that needs context to read. */
    val tail: List<String> = emptyList(),
    val stop: StopOutcome? = null,
    /** Structured crash/stage diagnostics (`MCS001` through `MCS011`) emitted during this case. */
    val diagnostics: List<DiagnosticFinding> = emptyList(),
) {
    val ok: Boolean get() = outcome == Outcome.PASSED
}

data class RunReport(
    val tool: String,
    val toolVersion: String,
    val scenario: String,
    val startedAt: Instant,
    val cases: List<CaseResult>,
    val scenarioFile: String? = null,
) {
    val passed: Int get() = cases.count { it.outcome == Outcome.PASSED }
    val failed: Int get() = cases.count { it.outcome == Outcome.FAILED }
    val errored: Int get() = cases.count { it.outcome == Outcome.ERROR }

    /** True when every case reached a verdict and every verdict was a pass. */
    val ok: Boolean get() = cases.isNotEmpty() && cases.all { it.ok }

    val elapsed: Duration get() = cases.fold(Duration.ZERO) { total, case -> total.plus(case.elapsed) }
}
