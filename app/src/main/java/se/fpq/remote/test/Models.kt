package se.fpq.remote.test

import kotlin.math.abs

// ===== SPOTIFY MODELS =====

data class SpotifyTrack(
    val id: String,
    val name: String,
    val artist: SpotifyArtist,
    val album: SpotifyAlbum,
    val durationMs: Int,
    val uri: String,
    val explicit: Boolean = false
)

data class SpotifyArtist(
    val id: String,
    val name: String,
    val uri: String
)

data class SpotifyAlbum(
    val id: String,
    val name: String,
    val uri: String,
    val images: List<SpotifyImage> = emptyList()
)

data class SpotifyImage(
    val url: String,
    val height: Int?,
    val width: Int?
)

data class SpotifyAudioFeatures(
    val id: String,
    val bpm: Int,
    val energy: Float = 0f,
    val danceability: Float = 0f,
    val valence: Float = 0f
)

data class SpotifyPlaylist(
    val id: String,
    val name: String,
    val uri: String,
    val trackCount: Int
)

// ===== PLAYLIST MODELS =====

data class PlaylistTrackSelection(
    val track: SpotifyTrack,
    val startTime: Int,
    val endTime: Int,
    val clipStart: Int,
    val clipEnd: Int
)

data class GeneratedPlaylist(
    val trackSelections: List<PlaylistTrackSelection>,
    val totalDuration: Int,
    val workoutId: String,
    val workoutName: String
) {
    val tracks: List<SpotifyTrack>
        get() = trackSelections.map { it.track }
}

data class CreatedPlaylist(
    val id: String,
    val name: String,
    val uri: String,
    val externalUrl: String,
    val trackCount: Int,
    val workoutId: String,
    val generatedPlaylist: GeneratedPlaylist
)

// ===== BPM MATCHING =====

data class BpmMatchingConfig(
    val exactMatch: Boolean = true,
    val multipleMatch: Boolean = true,
    val tolerancePercent: Int = 10
) {
    fun matches(targetCadence: Int, songBpm: Int): Boolean {
        return if (exactMatch && multipleMatch) {
            matchesExact(targetCadence, songBpm) || matchesMultiple(targetCadence, songBpm)
        } else if (exactMatch) {
            matchesExact(targetCadence, songBpm)
        } else if (multipleMatch) {
            matchesMultiple(targetCadence, songBpm)
        } else {
            false
        }
    }

    private fun matchesExact(targetCadence: Int, songBpm: Int): Boolean {
        val tolerance = (targetCadence * tolerancePercent) / 100
        return songBpm in (targetCadence - tolerance)..(targetCadence + tolerance)
    }

    private fun matchesMultiple(targetCadence: Int, songBpm: Int): Boolean {
        val targets = listOf(
            targetCadence,
            targetCadence * 2,
            targetCadence / 2
        )
        val tolerance = (targetCadence * tolerancePercent) / 100
        return targets.any { target ->
            songBpm in (target - tolerance)..(target + tolerance)
        }
    }
}

fun calculateBpmMatchScore(targetCadence: Int, songBpm: Int): Double {
    return when {
        targetCadence == 0 -> 0.0
        songBpm == targetCadence -> 100.0
        songBpm == targetCadence * 2 -> 90.0
        songBpm == targetCadence / 2 -> 85.0
        else -> {
            val diff = abs(songBpm - targetCadence)
            val maxDiff = targetCadence * 0.2
            if (diff <= maxDiff) {
                100.0 * (1.0 - (diff / maxDiff))
            } else {
                0.0
            }
        }
    }
}

