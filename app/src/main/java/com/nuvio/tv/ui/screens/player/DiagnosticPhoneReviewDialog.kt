package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nuvio.tv.core.diagnostics.DiagnosticShareLink
import com.nuvio.tv.core.diagnostics.StoredDiagnosticReport
import com.nuvio.tv.core.qr.QrCodeGenerator

@Composable
fun DiagnosticPhoneReviewDialog(link: DiagnosticShareLink, onDismiss: () -> Unit) {
    val qr = remember(link.url) { runCatching { QrCodeGenerator.generate(link.url, 420, margin = 1) }.getOrNull() }
    DisposableEffect(link.url) {
        onDispose { onDismiss() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Review report on your phone") },
        text = {
            Column(
                modifier = Modifier.widthIn(max = 560.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (qr != null) Image(qr.asImageBitmap(), contentDescription = "QR code for local diagnostic report")
                Text(
                    "Scan this code from the same local network. The page shows the full sanitized report, lets you copy or download it, and opens a GitHub issue form.",
                    textAlign = TextAlign.Center
                )
                Text(
                    "Nothing has been sent. Paste the report into GitHub, review it, then submit it yourself.",
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(2.dp))
                Text(link.url, modifier = Modifier.padding(4.dp), textAlign = TextAlign.Center)
            }
        },
        shape = RoundedCornerShape(18.dp)
    )
}

@Composable
fun DiagnosticShareErrorDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        title = { Text("Phone review unavailable") },
        text = { Text(message) }
    )
}

/** Used by About diagnostics and intentionally accepts all retained report types. */
@Composable
fun SavedDiagnosticReportsDialog(
    reports: List<StoredDiagnosticReport>,
    onReview: (StoredDiagnosticReport) -> Unit,
    onDiscard: (StoredDiagnosticReport) -> Unit,
    onDismiss: () -> Unit
) {
    val scrollState = rememberScrollState()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Saved diagnostic reports") },
        text = {
            Column(modifier = Modifier.verticalScroll(scrollState)) {
                if (reports.isEmpty()) {
                    Text("No saved diagnostic reports.")
                } else {
                    reports.asReversed().forEach { report ->
                        Text(report.type.replace('_', ' '), modifier = Modifier.padding(top = 8.dp))
                        Text(report.summary, modifier = Modifier.padding(bottom = 4.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = { onReview(report) }) { Text("Open phone review") }
                            TextButton(onClick = { onDiscard(report) }) { Text("Discard") }
                        }
                    }
                }
            }
        }
    )
}