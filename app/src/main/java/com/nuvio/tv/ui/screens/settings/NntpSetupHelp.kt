package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.FeatureDialog
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun NntpSetupHelpRow(onFocused: () -> Unit) {
    var showHelp by rememberSaveable { mutableStateOf(false) }
    SettingsActionRow(
        title = "NNTP / Usenet setup",
        subtitle = "Provider settings and troubleshooting",
        value = "Help",
        onClick = { showHelp = true },
        onFocused = onFocused
    )
    if (showHelp) {
        FeatureDialog(title = "NNTP / Usenet setup", onDismiss = { showHelp = false }) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                listOf(
                    "Configure your Usenet provider in the NZB addon. The addon sends the NZB link and server settings to Nuvio; Live TV provider settings are separate.",
                    "Use the host, TLS mode, port, username and password supplied by your provider. Nuvio supports nntps (TLS; default port 563) and nntp (plain; default port 119).",
                    "Keep the connection count within your provider's allowance, including other apps using that account. Raising it can cause connection-limit errors.",
                    "Missing articles: try another release. Indexer rate limit: wait for the displayed cooldown. Connection failure: check the provider settings and network.",
                    "For support, share the error category and build version only. Never share passwords, private NZB links or API keys."
                ).forEach { paragraph ->
                    Text(
                        text = paragraph,
                        modifier = Modifier.focusable(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextPrimary
                    )
                }
            }
        }
    }
}
