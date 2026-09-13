package com.nuvio.tv.core.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticShareHtmlTest {
    @Test
    fun `renders real local review page with iOS synchronous manual-copy fallback`() {
        val html = DiagnosticShareHtml.render(
            StoredDiagnosticReport("id", "playback_issue", 1L, "Safe summary", "Full sanitized report"),
            "high-entropy-token"
        )

        assertTrue(html.contains("textarea"))
        assertTrue(html.contains(".select()"))
        assertTrue(html.contains("setSelectionRange(0,x.value.length)"))
        assertTrue(html.contains("document.execCommand('copy')"))
        assertTrue(html.contains("/report.txt?t=high-entropy-token"))
        assertTrue(html.contains("https://github.com/Magichouse227/NuvioTV/issues/new"))
        assertTrue(html.contains("template=bug_report.yml"))
        assertTrue(html.contains("labels=bug"))
        assertTrue(html.contains("GitHub bug form"))
        assertTrue(html.contains("<strong>Logs</strong>"))
        assertFalse(html.contains("navigator.clipboard"))
    }

    @Test fun `report text is escaped without truncating the downloadable content`() {
        val report = "</pre><script>alert('unsafe')</script>" + "long report ".repeat(4_000)
        val html = DiagnosticShareHtml.render(
            StoredDiagnosticReport("id", "playback_issue", 1L, "Summary", report), "test-token"
        )
        assertFalse(html.contains("</pre><script>alert"))
        assertTrue(html.contains("&lt;/pre&gt;&lt;script&gt;"))
        assertTrue(html.contains("long report ".repeat(4_000)))
    }
}