package com.nuvio.tv.ui.screens.player

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.FeatureDialog
import java.util.Locale

internal fun isFireTvHd(): Boolean = Build.MANUFACTURER.equals("Amazon", true) &&
    (Build.MODEL.equals("AFTSS", true) || Build.MODEL.equals("AFTSSS", true))

@Composable
internal fun VideoQualityDialog(player: Player, onDismiss: () -> Unit) {
    var tracks by remember(player) { mutableStateOf(player.currentTracks) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(value: Tracks) { tracks = value }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    val options = remember(tracks) {
        tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.flatMap { group ->
            (0 until group.length).filter { index ->
                val format = group.getTrackFormat(index)
                group.isTrackSupported(index) && (!isFireTvHd() || (format.width <= 1920 && format.height <= 1080))
            }.map { group to it }
        }.sortedByDescending { (group, index) -> group.getTrackFormat(index).height }.take(64)
    }
    FeatureDialog("Video quality", onDismiss) {
        Text("Automatic adapts to the connection using supported video tracks.")
        Button(onClick = {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
            onDismiss()
        }) { Text("Automatic") }
        if (options.size <= 1) Text("This source has no additional video qualities. Choose another source for a different resolution.")
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(options) { (group, index) ->
                val format = group.getTrackFormat(index)
                val bitrate = format.bitrate.takeIf { it > 0 }?.let { " · " + String.format(Locale.US, "%.1f Mbps", it / 1_000_000.0) }.orEmpty()
                val resolution = if (format.height > 0) "${format.height}p" else "Video ${index + 1}"
                Button(onClick = {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index)).build()
                    onDismiss()
                }) { Text(resolution + bitrate + if (group.isTrackSelected(index)) " ✓" else "") }
            }
        }
    }
}
