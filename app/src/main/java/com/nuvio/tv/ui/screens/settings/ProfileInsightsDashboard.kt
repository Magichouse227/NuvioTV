package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Update
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.FeatureDialog
import com.nuvio.tv.ui.components.ProfileAvatarCircle
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun ProfileInsightsDialog(onDismiss: () -> Unit, viewModel: ProfileInsightsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val avatar by viewModel.avatar.collectAsStateWithLifecycle()
    FeatureDialog(stringResource(R.string.insights_title), onDismiss) {
        when {
            state.loading -> Text(stringResource(R.string.insights_loading), color = NuvioTheme.colors.TextSecondary)
            state.error -> {
                Text(stringResource(R.string.insights_error), color = NuvioTheme.colors.TextSecondary)
                Button(onClick = viewModel::refresh) { Text(stringResource(R.string.insights_retry)) }
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(4.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item(key = "profile") {
                    ProfileInsightsHero(state, avatar.second.takeIf { avatar.first == state.profile?.id })
                }
                item(key = "overview") { SectionTitle(stringResource(R.string.insights_overview)) }
                item(key = "overview_first_row") {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        MetricCard(state.continueWatching.toString(), R.string.insights_continue, R.string.insights_continue_sub,
                            Icons.Default.PlayArrow, Modifier.weight(1f))
                        MetricCard(state.upcoming.toString(), R.string.insights_upcoming, R.string.insights_upcoming_sub,
                            Icons.Default.Bookmark, Modifier.weight(1f))
                        MetricCard(state.completed.toString(), R.string.insights_completed, R.string.insights_completed_sub,
                            Icons.Default.Favorite, Modifier.weight(1f))
                    }
                }
                item(key = "overview_second_row") {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        MetricCard(state.ongoing.toString(), R.string.insights_ongoing, R.string.insights_ongoing_sub,
                            Icons.Default.Update, Modifier.weight(1f))
                        MetricCard(state.saved.toString(), R.string.insights_library, R.string.insights_library_sub,
                            Icons.Default.Bookmark, Modifier.weight(1f))
                        val watchTime = when {
                            state.watchTimeMs == 0L && state.missingRuntime > 0 -> "—"
                            state.watchTimeMs < 3_600_000L -> stringResource(R.string.insights_minutes, state.watchTimeMs / 60_000)
                            else -> stringResource(R.string.insights_hours, state.watchTimeMs / 3_600_000)
                        }
                        MetricCard(watchTime, R.string.insights_watch_time, R.string.insights_watch_time_sub,
                            Icons.Default.Schedule, Modifier.weight(1f))
                    }
                }
                item(key = "taste_heading") { SectionTitle(stringResource(R.string.insights_taste)) }
                item(key = "taste") { TasteDnaCard(state) }
                item(key = "coverage") {
                    InsightsSurface {
                        Text(stringResource(R.string.insights_estimate_note), style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.TextSecondary)
                        if (state.missingRuntime > 0) Text(stringResource(R.string.insights_missing_runtime, state.missingRuntime),
                            style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
                        if (state.metadataTotal > 0) Text(stringResource(
                            if (state.refreshing) R.string.insights_metadata_loading else R.string.insights_metadata_coverage,
                            state.metadataChecked, state.metadataTotal), style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.TextSecondary)
                    }
                }
                item(key = "recent_heading") { SectionTitle(stringResource(R.string.insights_recent)) }
                if (state.recent.isEmpty()) item(key = "empty") {
                    InsightsSurface { Text(stringResource(R.string.insights_recent_empty), color = NuvioTheme.colors.TextSecondary) }
                }
                items(state.recent, key = { "${it.contentType}:${it.contentId}:${it.season}:${it.episode}" }) { watched ->
                    InsightsSurface {
                        val episode = if (watched.season != null && watched.episode != null)
                            stringResource(R.string.insights_episode, watched.season, watched.episode) else ""
                        Text(watched.title + episode, style = MaterialTheme.typography.titleMedium,
                            color = NuvioTheme.colors.TextPrimary)
                    }
                }
            }
        }
    }
}

/** Read-only cards participate in focus, letting a remote scroll through the complete dashboard. */
@Composable
private fun InsightsSurface(
    modifier: Modifier = Modifier,
    brush: Brush? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(22.dp)
    val surface = NuvioTheme.colors.TextPrimary.copy(alpha = 0.075f)
    Column(
        modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .then(if (brush != null) Modifier.background(brush) else Modifier.background(surface))
            .border(if (focused) 2.dp else 1.dp,
                if (focused) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextPrimary.copy(alpha = 0.08f), shape)
            .focusable().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content
    )
}

@Composable
private fun ProfileInsightsHero(state: ProfileInsights, avatarUrl: String?) {
    val accent = NuvioTheme.colors.Secondary
    InsightsSurface(brush = Brush.linearGradient(listOf(accent.copy(alpha = 0.30f), accent.copy(alpha = 0.04f)))) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            ProfileAvatarCircle(name = state.profile?.name.orEmpty(), colorHex = state.profile?.avatarColorHex ?: "#388E3C",
                avatarImageUrl = avatarUrl, size = 76.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.insights_profile_name, state.profile?.name.orEmpty()),
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                    color = NuvioTheme.colors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.insights_snapshot), style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary)
            }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HeroCount(state.continueWatching, R.string.insights_continue, Modifier.weight(1f))
            HeroCount(state.saved, R.string.insights_library, Modifier.weight(1f))
            HeroCount(state.upcoming, R.string.insights_upcoming, Modifier.weight(1f))
        }
    }
}

@Composable
private fun HeroCount(value: Int, label: Int, modifier: Modifier) {
    Row(modifier.clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.08f))
        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(value.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            color = NuvioTheme.colors.TextPrimary)
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge,
            color = NuvioTheme.colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MetricCard(value: String, label: Int, subtitle: Int, icon: ImageVector, modifier: Modifier) {
    InsightsSurface(modifier.heightIn(min = 152.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                color = NuvioTheme.colors.TextPrimary, modifier = Modifier.weight(1f))
            AccentIcon(icon)
        }
        Text(stringResource(label), style = MaterialTheme.typography.titleMedium, color = NuvioTheme.colors.TextPrimary)
        Text(stringResource(subtitle), style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
    }
}

@Composable
private fun TasteDnaCard(state: ProfileInsights) {
    val accent = NuvioTheme.colors.Secondary
    InsightsSurface {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AccentIcon(Icons.Default.AutoAwesome)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(R.string.insights_taste_dna), style = MaterialTheme.typography.labelLarge,
                    color = NuvioTheme.colors.TextSecondary)
                Text(state.genres.firstOrNull()?.name ?: stringResource(R.string.insights_learning),
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = NuvioTheme.colors.TextPrimary)
                Text(stringResource(R.string.insights_taste_sub), style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary)
            }
        }
        if (state.genres.isEmpty()) Text(stringResource(R.string.insights_genres_empty),
            style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary)
        state.genres.forEach { genre ->
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Text(genre.name, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = NuvioTheme.colors.TextPrimary)
                Text("${genre.percent}%", style = MaterialTheme.typography.labelLarge, color = NuvioTheme.colors.TextSecondary)
            }
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(accent.copy(alpha = 0.12f))) {
                Box(Modifier.fillMaxWidth(genre.percent / 100f).fillMaxHeight().background(accent))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Text(stringResource(R.string.insights_balance), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                color = NuvioTheme.colors.TextPrimary)
            Text(stringResource(when {
                state.movieShare == null -> R.string.insights_learning
                state.movieShare >= 0.62f -> R.string.insights_movie_leaning
                state.movieShare <= 0.38f -> R.string.insights_series_leaning
                else -> R.string.insights_balanced
            }), style = MaterialTheme.typography.labelLarge, color = NuvioTheme.colors.TextSecondary)
        }
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))
            .background(if (state.movieShare == null) Color.Gray.copy(alpha = 0.25f) else accent)) {
            state.movieShare?.let { share -> Box(Modifier.fillMaxWidth(share).fillMaxHeight().background(Color.Gray)) }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.insights_movies), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary)
            Text(stringResource(R.string.insights_series), style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary)
        }
        if (state.badges.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            state.badges.forEach { badge ->
                Row(Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).background(accent.copy(alpha = 0.10f))
                    .border(1.dp, accent.copy(alpha = 0.28f), RoundedCornerShape(24.dp)).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = accent, modifier = Modifier.size(17.dp))
                    Text(stringResource(badge.labelResource()), style = MaterialTheme.typography.labelMedium,
                        color = NuvioTheme.colors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun InsightsBadge.labelResource(): Int = when (this) {
    InsightsBadge.MOVIE_FAN -> R.string.insights_badge_movie
    InsightsBadge.SERIES_FAN -> R.string.insights_badge_series
    InsightsBadge.EXPLORER -> R.string.insights_badge_explorer
    InsightsBadge.BINGE_READY -> R.string.insights_badge_binge
    InsightsBadge.HIGHLY_ACTIVE -> R.string.insights_badge_active
    InsightsBadge.COLLECTOR -> R.string.insights_badge_collector
    InsightsBadge.RELEASE_RADAR -> R.string.insights_badge_radar
    InsightsBadge.COMPLETIONIST -> R.string.insights_badge_completionist
}

@Composable
private fun AccentIcon(icon: ImageVector) {
    Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(NuvioTheme.colors.Secondary.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = NuvioTheme.colors.Secondary, modifier = Modifier.size(23.dp))
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        color = NuvioTheme.colors.TextSecondary, modifier = Modifier.padding(top = 4.dp))
}
