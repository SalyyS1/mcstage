package com.salyvn.mcstage.report

import com.salyvn.mcstage.run.Outcome
import com.salyvn.mcstage.run.RunReport

/**
 * Self-contained dark-mode HTML matrix report renderer (`--format html` or `--html <path>`).
 *
 * Zero external CDN dependencies, safe HTML escaping, ready to upload directly as a GitHub Actions
 * CI artifact or share in pull requests.
 */
class HtmlRunReporter {

    fun render(report: RunReport): String {
        val verdict = if (report.ok) "PASS" else "FAIL"
        val verdictColor = if (report.ok) "#22c55e" else "#ef4444"
        val casesHtml = buildString {
            for (c in report.cases) {
                val badgeCls = when (c.outcome) {
                    Outcome.PASSED -> "ok"
                    Outcome.FAILED -> "err"
                    Outcome.ERROR -> "warn"
                }
                append("<section class=\"case\">")
                append("<div class=\"case-head\">")
                append("<div><strong>").append(esc(c.case.platform.id.uppercase())).append(" ").append(esc(c.case.version)).append("</strong>")
                append(" <span class=\"muted\">(").append(c.elapsed.toMillis()).append(" ms)</span></div>")
                append("<span class=\"badge ").append(badgeCls).append("\">").append(c.outcome.name).append("</span>")
                append("</div>")
                c.failure?.let { f ->
                    append("<div class=\"fail-box\">").append(esc(f)).append("</div>")
                }
                if (c.steps.isNotEmpty()) {
                    append("<table><thead><tr><th>Step</th><th>Status</th><th class=\"num\">Elapsed</th><th>Matched / Detail</th></tr></thead><tbody>")
                    for (st in c.steps) {
                        val sCls = if (st.ok) "ok" else "err"
                        val sLbl = if (st.ok) "PASS" else "FAIL"
                        val info = st.detail ?: st.matched.orEmpty()
                        append("<tr><td><code>").append(esc(st.describe)).append("</code></td>")
                        append("<td><span class=\"badge ").append(sCls).append("\">").append(sLbl).append("</span></td>")
                        append("<td class=\"num\">").append(st.elapsed.toMillis()).append(" ms</td>")
                        append("<td>").append(esc(info)).append("</td></tr>")
                    }
                    append("</tbody></table>")
                }
                if (c.diagnostics.isNotEmpty()) {
                    append("<div class=\"diags\">")
                    for (d in c.diagnostics) {
                        append("<div class=\"diag\"><code>").append(esc(d.ruleId)).append("</code> [")
                            .append(esc(d.severity)).append("] ").append(esc(d.message)).append("</div>")
                    }
                    append("</div>")
                }
                append("</section>\n")
            }
        }

        return """<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>mcstage Report — ${esc(report.scenario)}</title>
  <style>
    :root { color-scheme: dark; }
    body { margin:0; background:#0b0f17; color:#e6edf7; font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif; line-height:1.5; }
    .wrap { max-width:1040px; margin:0 auto; padding:28px 20px 56px; }
    .hero { background:#121826; border:1px solid #26324d; border-radius:12px; padding:20px 22px; display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:14px; }
    .hero h1 { margin:0 0 4px; font-size:1.45rem; }
    .muted { color:#93a4c3; font-size:0.88rem; }
    .verdict { font-weight:800; padding:7px 16px; border-radius:999px; border:1px solid $verdictColor; color:$verdictColor; }
    .kpis { display:grid; grid-template-columns:repeat(auto-fit,minmax(160px,1fr)); gap:12px; margin:18px 0 22px; }
    .kpi { background:#121826; border:1px solid #26324d; border-radius:10px; padding:14px; }
    .kpi span { display:block; color:#93a4c3; font-size:0.78rem; text-transform:uppercase; }
    .kpi strong { font-size:1.3rem; color:#38bdf8; }
    .case { background:#121826; border:1px solid #26324d; border-radius:12px; padding:16px 18px; margin-bottom:16px; }
    .case-head { display:flex; justify-content:space-between; align-items:center; margin-bottom:10px; }
    .badge { display:inline-block; padding:2px 9px; border-radius:999px; font-size:0.74rem; font-weight:700; }
    .badge.ok { background:rgba(34,197,94,0.16); color:#22c55e; }
    .badge.err { background:rgba(239,68,68,0.16); color:#ef4444; }
    .badge.warn { background:rgba(245,158,11,0.16); color:#f59e0b; }
    .fail-box { background:rgba(239,68,68,0.12); border:1px solid rgba(239,68,68,0.4); color:#fca5a5; padding:8px 12px; border-radius:8px; margin-bottom:10px; font-family:monospace; font-size:0.85rem; }
    table { width:100%; border-collapse:collapse; font-size:0.88rem; }
    th, td { text-align:left; padding:7px 9px; border-bottom:1px solid #1f2b42; }
    th { color:#93a4c3; font-weight:600; }
    .num { text-align:right; font-variant-numeric:tabular-nums; }
    .diags { margin-top:10px; display:flex; flex-direction:column; gap:6px; }
    .diag { background:#0d1422; border:1px solid #26324d; padding:7px 10px; border-radius:6px; font-family:monospace; font-size:0.82rem; }
    footer { text-align:center; color:#7c8fae; font-size:0.8rem; margin-top:24px; }
  </style>
</head>
<body>
  <div class="wrap">
    <header class="hero">
      <div>
        <h1>${esc(report.scenario)}</h1>
        <div class="muted">${esc(report.tool)} v${esc(report.toolVersion)} · started ${esc(report.startedAt.toString())}</div>
      </div>
      <div class="verdict">$verdict (${report.passed}/${report.cases.size})</div>
    </header>
    <section class="kpis">
      <div class="kpi"><span>Passed</span><strong>${report.passed}</strong></div>
      <div class="kpi"><span>Failed</span><strong>${report.failed}</strong></div>
      <div class="kpi"><span>Errored</span><strong>${report.errored}</strong></div>
      <div class="kpi"><span>Total Time</span><strong>${report.elapsed.toMillis()} ms</strong></div>
    </section>
    $casesHtml
    <footer>Generated locally by mcstage v${esc(report.toolVersion)} · Real Paper / Folia / Purpur integration matrix.</footer>
  </div>
</body>
</html>
"""
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}
