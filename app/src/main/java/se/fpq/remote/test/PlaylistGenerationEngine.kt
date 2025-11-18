package se.fpq.remote.test

import android.util.Log

private const val TAG = "PlaylistGenerationEngine"

/**
 * Generates a playlist by matching songs to workout blocks based on BPM/cadence
 */
class PlaylistGenerationEngine {
    
    private fun log(message: String) {
        Log.d(TAG, message)
    }

    /**
     * Generate a playlist from available tracks to match workout blocks
     */
    fun generatePlaylist(
        workout: Workout,
        availableTracks: List<SpotifyTrack>,
        trackFeatures: Map<String, SpotifyAudioFeatures>,
        bpmConfig: BpmMatchingConfig
    ): Result<GeneratedPlaylist> = try {
        val playlistTracks = mutableListOf<PlaylistTrackSelection>()
        val usedTrackIds = mutableSetOf<String>()
        var workoutElapsedTime = 0
        var globalTrackIndexOffset = 0  // Track round-robin position across blocks
        
        for ((blockIndex, block) in workout.blocks.withIndex()) {
            val blockDuration = block.duration
            val targetCadence = block.cadence
            log("📍 Block ${blockIndex + 1}: ${blockDuration}s, Cadence: ${targetCadence ?: "N/A"} RPM")
            
            // Sort all tracks by BPM relevance instead of filtering them out
            val sortedTracks = if (targetCadence != null) {
                availableTracks.sortedBy { track ->
                    val features = trackFeatures[track.id]
                    if (features == null) {
                        // Tracks without BPM data get lowest priority
                        Int.MAX_VALUE
                    } else {
                        // Calculate BPM distance (lower is better)
                        val tolerance = (targetCadence * 20) / 100
                        val distance = when {
                            // Perfect match (within tight tolerance)
                            features.bpm in (targetCadence - tolerance)..(targetCadence + tolerance) -> 
                                Math.abs(features.bpm - targetCadence)
                            // Harmonic match (2x or 0.5x BPM)
                            features.bpm in ((targetCadence * 2) - tolerance * 2)..((targetCadence * 2) + tolerance * 2) ->
                                Math.abs(features.bpm - (targetCadence * 2)) + 1000
                            features.bpm in ((targetCadence / 2) - tolerance)..((targetCadence / 2) + tolerance) ->
                                Math.abs(features.bpm - (targetCadence / 2)) + 1000
                            // Close match (within 25% tolerance)
                            features.bpm in (targetCadence * 0.75).toInt()..(targetCadence * 1.25).toInt() ->
                                Math.abs(features.bpm - targetCadence) + 2000
                            // All other tracks
                            else -> Math.abs(features.bpm - targetCadence) + 5000
                        }
                        distance
                    }
                }
            } else {
                // No target cadence, use all tracks without sorting
                availableTracks
            }
            
            // Filter out already used tracks, but keep all remaining tracks available
            var tracksForBlock = sortedTracks.filterNot { it.id in usedTrackIds }
            log("   tracksForBlock after filtering: ${tracksForBlock.size} tracks (from ${sortedTracks.size} sorted tracks)")
            log("   First 5: ${tracksForBlock.take(5).map { it.name }.joinToString(", ")}")
            
            if (tracksForBlock.isEmpty()) {
                log("⚠️ All tracks used for block ${blockIndex + 1}, allowing reuse of available tracks")
                tracksForBlock = sortedTracks
            }
            
            val (blockTracks, nextTrackIndex) = selectTracksForBlock(
                tracksForBlock,
                blockDuration,
                trackFeatures,
                workoutElapsedTime,
                usedTrackIds,
                globalTrackIndexOffset
            )
            
            playlistTracks.addAll(blockTracks)
            log("  ✅ Selected ${blockTracks.size} songs for block ${blockIndex + 1}: ${blockTracks.map { it.track.name }.take(2).joinToString(", ")}${if (blockTracks.size > 2) ", ..." else ""}")
            
            blockTracks.forEach { usedTrackIds.add(it.track.id) }
            
            // Update global track index for next block using the actual index from selectTracksForBlock
            globalTrackIndexOffset = nextTrackIndex
            
            workoutElapsedTime += blockDuration
        }
        
        Result.success(
            GeneratedPlaylist(
                trackSelections = playlistTracks,
                totalDuration = workoutElapsedTime,
                workoutId = workout.id,
                workoutName = workout.name
            )
        )
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun selectTracksForBlock(
        availableTracks: List<SpotifyTrack>,
        blockDuration: Int,
        trackFeatures: Map<String, SpotifyAudioFeatures>,
        workoutElapsedTime: Int,
        usedTrackIds: MutableSet<String>,
        globalTrackIndexOffset: Int
    ): Pair<List<PlaylistTrackSelection>, Int> {
        val selectedTracks = mutableListOf<PlaylistTrackSelection>()
        var remainingDuration = blockDuration
        var blockStartTime = workoutElapsedTime
        
        log("🔍 selectTracksForBlock: availableTracks=${availableTracks.size}, usedTrackIds=${usedTrackIds.size}, blockDuration=${blockDuration}s, offset=${globalTrackIndexOffset}")
        
        // First pass: try to use only unused tracks
        var tracksToUse = availableTracks.filterNot { it.id in usedTrackIds }
        log("   Unused tracks: ${tracksToUse.size} (${tracksToUse.map { it.name }.take(3).joinToString(", ")}${if (tracksToUse.size > 3) "..." else ""})")
        
        // If all tracks are used, fall back to all tracks
        if (tracksToUse.isEmpty()) {
            log("   ⚠️ All tracks used! Falling back to all available tracks")
            tracksToUse = availableTracks
        }
        
        // Start from the global offset to ensure round-robin across blocks
        // Use the ORIGINAL availableTracks list size for offset calculation to stay consistent
        val startIndex = globalTrackIndexOffset % availableTracks.size
        var trackIndex = startIndex
        var cycleCount = 0
        var tracksAdded = 0
        var startedCycle = false
        
        while (remainingDuration > 0 && tracksToUse.isNotEmpty()) {
            val track = tracksToUse[trackIndex % tracksToUse.size]
            val trackDurationMs = track.durationMs
            val trackDurationSec = trackDurationMs / 1000
            
            log("   Trying track[${trackIndex % tracksToUse.size}]: '${track.name}' (${trackDurationSec}s, need ${remainingDuration}s)")
            
            if (trackDurationSec <= remainingDuration) {
                selectedTracks.add(
                    PlaylistTrackSelection(
                        track = track,
                        startTime = blockStartTime,
                        endTime = blockStartTime + trackDurationSec,
                        clipStart = 0,
                        clipEnd = trackDurationSec
                    )
                )
                log("   ✅ Added: '${track.name}'")
                blockStartTime += trackDurationSec
                remainingDuration -= trackDurationSec
                tracksAdded++
            } else if (remainingDuration >= 30) {
                selectedTracks.add(
                    PlaylistTrackSelection(
                        track = track,
                        startTime = blockStartTime,
                        endTime = blockStartTime + remainingDuration,
                        clipStart = 0,
                        clipEnd = remainingDuration
                    )
                )
                log("   ✅ Added (clipped): '${track.name}' (${remainingDuration}s)")
                blockStartTime += remainingDuration
                remainingDuration = 0
                tracksAdded++
            }
            
            trackIndex = (trackIndex + 1) % tracksToUse.size
            
            // Check if we've completed a cycle through tracksToUse
            if (trackIndex == startIndex && startedCycle) {
                cycleCount++
                log("   🔄 Cycle ${cycleCount}: completed pass through all tracks")
                // Don't cycle more than twice through the same set
                if (cycleCount >= 2) {
                    log("   ⛔ Breaking: reached max cycles (2)")
                    break
                }
            }
            startedCycle = true
        }
        
        log("   📊 Selected ${tracksAdded} tracks for block, next offset will be $trackIndex")
        
        return Pair(selectedTracks, trackIndex)
    }
}

