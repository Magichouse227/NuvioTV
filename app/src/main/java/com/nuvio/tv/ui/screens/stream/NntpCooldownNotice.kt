package com.nuvio.tv.ui.screens.stream

import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.core.usenet.NntpRateLimit
import kotlinx.coroutines.delay

/** A persistent notice, not a toast or a loader that implies an automatic retry. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun NntpCooldownNotice(rateLimit: NntpRateLimit, onRetry: () -> Unit) {
    var now by remember(rateLimit) { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(rateLimit) {
        do {
            now = SystemClock.elapsedRealtime()
            if (rateLimit.remainingSeconds(now) == 0L) break
            delay(250L)
        } while (true)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
        Text(
            text = rateLimit.userMessage(now),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Button(
            onClick = onRetry,
            enabled = rateLimit.remainingSeconds(now) == 0L,
            modifier = Modifier.padding(top = 12.dp)
        ) {
            Text("Retry selected stream")
        }
    }
}