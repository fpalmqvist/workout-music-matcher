package se.fpq.remote.test

import android.util.Log
import kotlin.random.Random

private const val TAG = "PlaylistGenerationEngine"

/**
 * Generates a playlist by matching songs to workout blocks based on BPM/cadence
 * Includes randomization to avoid repetitive track selection
 */
class PlaylistGenerationEngine {
    
    // Threshold: tracks within this score of the best match are considered "good enough"
    // For example, 5 means "within 5 points of best score"
    // This handles multiples (65 BPM and 130 BPM treated equally for 65 RPM cadence)
    private val goodEnoughThresholdScore = 5
    
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
                    calculateBpmScore(track, targetCadence, trackFeatures)
                }
            } else {
                // No target cadence, use all tracks without sorting (use them all equally)
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
                globalTrackIndexOffset,
                targetCadence
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
                workoutName = workout.name,
                sourceAllTracks = availableTracks,  // Store full source for substitution
                trackFeatures = trackFeatures  // Store features for BPM matching
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
        globalTrackIndexOffset: Int,
        targetCadence: Int?
    ): Pair<List<PlaylistTrackSelection>, Int> {
        val selectedTracks = mutableListOf<PlaylistTrackSelection>()
        var remainingDuration = blockDuration
        var blockStartTime = workoutElapsedTime
        
        log("🔍 selectTracksForBlock: availableTracks=${availableTracks.size}, usedTrackIds=${usedTrackIds.size}, blockDuration=${blockDuration}s, offset=${globalTrackIndexOffset}")
        
        // First pass: try to use only unused tracks (sorted by BPM match quality)
        var tracksToUse = availableTracks.filterNot { it.id in usedTrackIds }
        log("   Unused tracks: ${tracksToUse.size} (${tracksToUse.map { it.name }.take(3).joinToString(", ")}${if (tracksToUse.size > 3) "..." else ""})")
        
        // If all tracks are used, fall back to all tracks
        if (tracksToUse.isEmpty()) {
            log("   ⚠️ All tracks used! Falling back to all available tracks")
            tracksToUse = availableTracks
        }
        
        // For initial selection, pick from "good enough" tracks with randomization
        // This avoids always picking the same best track
        var trackIndex = 0
        var tracksAdded = 0
        var usedIndexes = mutableSetOf<Int>()
        
        while (remainingDuration > 0 && trackIndex < tracksToUse.size) {
            // Select a random "good enough" track from remaining tracks
            val selectedIndex = selectGoodEnoughTrackIndex(tracksToUse, usedIndexes, trackFeatures, targetCadence)
            
            if (selectedIndex == -1) {
                log("   ⚠️ No more good tracks available")
                break
            }
            
            val track = tracksToUse[selectedIndex]
            val trackDurationMs = track.durationMs
            val trackDurationSec = trackDurationMs / 1000
            
            log("   Trying track[${selectedIndex}]: '${track.name}' (${trackDurationSec}s, need ${remainingDuration}s)")
            
            if (trackDurationSec <= remainingDuration) {
                // Get top 3 alternative tracks
                val alternatives = getAlternativeTracks(track, tracksToUse, 3)
                
                selectedTracks.add(
                    PlaylistTrackSelection(
                        track = track,
                        startTime = blockStartTime,
                        endTime = blockStartTime + trackDurationSec,
                        clipStart = 0,
                        clipEnd = trackDurationSec,
                        alternatives = alternatives
                    )
                )
                log("   ✅ Added: '${track.name}' (${alternatives.size} alternatives available)")
                blockStartTime += trackDurationSec
                remainingDuration -= trackDurationSec
                tracksAdded++
                usedIndexes.add(selectedIndex)
            } else if (remainingDuration >= 30) {
                // Get top 3 alternative tracks
                val alternatives = getAlternativeTracks(track, tracksToUse, 3)
                
                selectedTracks.add(
                    PlaylistTrackSelection(
                        track = track,
                        startTime = blockStartTime,
                        endTime = blockStartTime + remainingDuration,
                        clipStart = 0,
                        clipEnd = remainingDuration,
                        alternatives = alternatives
                    )
                )
                log("   ✅ Added (clipped): '${track.name}' (${remainingDuration}s, ${alternatives.size} alternatives available)")
                blockStartTime += remainingDuration
                remainingDuration = 0
                tracksAdded++
                usedIndexes.add(selectedIndex)
            } else {
                // Track too long and can't fit, mark as used and try next
                usedIndexes.add(selectedIndex)
            }
            
            trackIndex++
        }
        
        log("   📊 Selected ${tracksAdded} tracks for block")
        
        return Pair(selectedTracks, trackIndex)
    }
    
    /**
     * Select a random track that is "good enough" (within score threshold of best match)
     * Returns -1 if no good tracks remain
     */
    private fun selectGoodEnoughTrackIndex(
        sortedTracks: List<SpotifyTrack>,
        usedIndexes: Set<Int>,
        trackFeatures: Map<String, SpotifyAudioFeatures>,
        targetCadence: Int?
    ): Int {
        // If no valid target cadence, just do random selection
        if (targetCadence == null || targetCadence < 0) {
            log("     🎲 No target cadence - selecting random from all available")
            val availableTracks = sortedTracks.indices.filter { it !in usedIndexes }
            
            if (availableTracks.isEmpty()) {
                return -1
            }
            
            val randomIndex = availableTracks.random()
            log("     Selected ${sortedTracks[randomIndex].name} randomly from ${availableTracks.size} available options")
            
            return randomIndex
        }
        
        // Get the best track's score (first in sorted list, already sorted by score)
        val bestTrack = sortedTracks.firstOrNull { it.id !in usedIndexes.map { idx -> sortedTracks[idx].id } } 
            ?: run {
                // If all unused tracks are gone, pick from any available
                val availableTracks = sortedTracks.indices.filter { it !in usedIndexes }
                if (availableTracks.isEmpty()) return -1
                return availableTracks.random()
            }
        
        val bestScore = calculateBpmScore(bestTrack, targetCadence, trackFeatures)
        val scoreThreshold = bestScore + goodEnoughThresholdScore
        
        log("     🎲 Score threshold: ${bestScore} (within ±${goodEnoughThresholdScore} points, includes multiples)")
        
        // Get all unused tracks that are "good enough" by score (accounts for multiples like 65 BPM and 130 BPM)
        val goodEnoughTracks = sortedTracks.mapIndexed { index, track ->
            if (index !in usedIndexes) {
                val score = calculateBpmScore(track, targetCadence, trackFeatures)
                if (score <= scoreThreshold) {
                    index to track
                } else {
                    null
                }
            } else {
                null
            }
        }.filterNotNull()
        
        if (goodEnoughTracks.isEmpty()) {
            log("     ⚠️ No good enough tracks found, selecting random from all available")
            
            // Get all unused tracks and randomly select one
            val availableTracks = sortedTracks.indices.filter { it !in usedIndexes }
            
            if (availableTracks.isEmpty()) {
                return -1
            }
            
            val randomIndex = availableTracks.random()
            log("     Selected ${sortedTracks[randomIndex].name} randomly from ${availableTracks.size} available options")
            
            return randomIndex
        }
        
        // Randomly select from good enough tracks
        val randomTrack = goodEnoughTracks.random()
        log("     Selected ${randomTrack.second.name} from ${goodEnoughTracks.size} good enough options")
        
        return randomTrack.first
    }
    
    /**
     * Get the next best alternative tracks, excluding the current track
     */
    private fun getAlternativeTracks(
        currentTrack: SpotifyTrack,
        availableTracks: List<SpotifyTrack>,
        maxCount: Int
    ): List<SpotifyTrack> {
        return availableTracks
            .filterNot { it.id == currentTrack.id }  // Exclude current track
            .take(maxCount)  // Get top maxCount alternatives (they're already sorted by BPM match)
    }
    
    /**
     * Calculate BPM score for a track (lower is better)
     * All multiples are treated equally: 75 BPM matches 75 RPM same as 150 BPM matches 75 RPM
     */
    private fun calculateBpmScore(
        track: SpotifyTrack,
        targetCadence: Int,
        trackFeatures: Map<String, SpotifyAudioFeatures>
    ): Int {
        val features = trackFeatures[track.id]
        
        if (features == null || features.bpm < 0) {
            return Int.MAX_VALUE / 2  // Penalize missing BPM heavily
        }
        
        val tolerance = (targetCadence * 25) / 100  // 25% tolerance
        val multipliers = listOf(1, 2, 3, 4)
        var bestDistance = Int.MAX_VALUE
        
        // Find best match among all multiples
        for (multiplier in multipliers) {
            val targetBpm = targetCadence * multiplier
            val matchTolerance = tolerance * multiplier
            
            if (features.bpm in (targetBpm - matchTolerance)..(targetBpm + matchTolerance)) {
                // Match found! Score is pure distance, no multiplier penalty
                val distance = Math.abs(features.bpm - targetBpm)
                
                if (distance < bestDistance) {
                    bestDistance = distance
                }
            }
        }
        
        // If found a multiple match, return its score
        if (bestDistance != Int.MAX_VALUE) {
            return bestDistance
        }
        
        // No multiple match - penalize heavily as fallback
        return when {
            features.bpm in (targetCadence * 0.75).toInt()..(targetCadence * 1.25).toInt() ->
                Math.abs(features.bpm - targetCadence) + 30000
            else -> Math.abs(features.bpm - targetCadence) + 35000
        }
    }
}

