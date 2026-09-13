package com.nuvio.tv.core.diagnostics

/** Pure renderer: the local HTTP server only supplies this already-sanitized, static page. */
internal object DiagnosticShareHtml {
    fun render(report: StoredDiagnosticReport, token: String): String {
        val safeReport = html(report.body)
        val title = html(report.summary.ifBlank { report.type })
        val issueUrl = "https://github.com/Magichouse227/NuvioTV/issues/new?template=bug_report.yml&labels=bug&title=" +
            java.net.URLEncoder.encode("NuvioTV ${report.type} report", "UTF-8")
        return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<title>NuvioTV report</title><style>body{font:16px system-ui;margin:20px;background:#101114;color:#f6f6f6}button,a{display:inline-block;margin:5px 8px 14px 0;padding:12px;color:#fff;background:#356ae6;border:0;border-radius:6px;text-decoration:none}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#1c1e24;padding:14px;border-radius:6px}</style></head><body>
<h1>Review NuvioTV diagnostic report</h1><p>$title</p><p>This page is shared only on your local network for a short time. It does not upload anything.</p>
<button onclick="copyReport()">Copy full report</button><span id="copy-status" role="status"></span><a href="/report.txt?t=$token">Download .txt</a><a href="$issueUrl" target="_blank" rel="noreferrer">Open GitHub new issue</a>
 <p><strong>Next:</strong> paste the full report into the <strong>Logs</strong> field of the GitHub bug form, describe what happened, review it, then submit the issue yourself. Opening GitHub does not send the report automatically.</p>
<pre id="report">$safeReport</pre><script>function copyReport(){var t=document.getElementById('report').innerText,x=document.createElement('textarea'),s=document.getElementById('copy-status');x.value=t;x.setAttribute('readonly','');x.style.position='fixed';x.style.opacity='0';document.body.appendChild(x);x.focus();x.select();x.setSelectionRange(0,x.value.length);var ok=false;try{ok=document.execCommand('copy')}catch(e){}x.remove();if(ok){s.textContent='Copied.'}else{s.textContent='Copy was unavailable. Select the report text below and copy it manually.';document.getElementById('report').setAttribute('tabindex','0')}}</script></body></html>"""
    }

    private fun html(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}