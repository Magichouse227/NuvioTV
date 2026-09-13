package com.nuvio.tv.core.usenet

import org.junit.Assert.*
import org.junit.Test

class NntpNativeDiagnosticsTest {
    @Test fun retainsOnlyNumericStructuralEvents() {
        assertEquals(
            "event=media_read_error offset=123 bytes=42",
            NntpNativeDiagnostics.parse("2026/09/13 NUVIO_DIAG event=media_read_error offset=123 bytes=42")
        )
        assertEquals("event=session_created", NntpNativeDiagnostics.parse("NUVIO_DIAG event=session_created"))
    }

    @Test fun rejectsUnstructuredAndCredentialBearingLogs() {
        listOf(
            "AUTHINFO PASS supersecret",
            "request https://user:secret@host/key/123/file.nzb",
            "NUVIO_DIAG event=media_read_error offset=123 url=https://private",
            "NUVIO_DIAG event=unknown secret",
            "NUVIO_DIAG event=media_read_error bytes=secret",
            "NUVIO_DIAG event=media_read_error\nAuthorization: Bearer token"
        ).forEach { assertNull(NntpNativeDiagnostics.parse(it)) }
    }
}