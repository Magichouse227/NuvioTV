package com.nuvio.tv.core.diagnostics

data class StoredDiagnosticReport(
    val id: String,
    val type: String,
    val createdAtMs: Long,
    val summary: String,
    val body: String
)