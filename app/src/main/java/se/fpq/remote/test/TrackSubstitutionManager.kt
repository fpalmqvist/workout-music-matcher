package se.fpq.remote.test

import android.util.Log
import kotlin.math.abs

private const val TAG = "TrackSubstitution"

/**
 * Manages track substitution during playback with intelligent BPM matching
 * Considers cadence multiples (1x, 2x, 3x, 4x) for better track selection
 */
class TrackSubstitutionManager(
    private val allTracks: List<SpotifyTrack>,
    private val trackFeatures: Map<String, SpotifyAudioFeatures>
) {
    // Cache of tracks sorted by cadence (cadence -> sorted track list)
    private val cadenceSortCache = mutableMapOf<Int, List<SpotifyTrack>>()
    
    // Round-robin indices for each cadence (to track where we are in the sorted list)
    private val cadenceIndices = mutableMapOf<Int, Int>()
    
    // Track usage count to support round-robin cycling
    private val usageCount = mutableMapOf<String, Int>()
    
    // Track which tracks are currently in use in the playlist
    private val playlistTracks = mutableSetOf<String>()
    
    /**
     * Update the set of tracks currently in the playlist
     */
    fun setPlaylistTracks(trackIds: Set<String>) {
        playlistTracks.clear()
        playlistTracks.addAll(trackIds)
        Log.d(TAG, "📋 Updated playlist tracks: ${trackIds.size} tracks in use")
    }
    
    /**
     * Get the next substitution for a track, considering cadence and BPM matching
     * @param currentTrack the track to replace
     * @param cadence the target cadence (RPM) - if null, return any next unused track
     * @return the next best alternative track
     */
    fun getNextSubstitution(currentTrack: SpotifyTrack, cadence: Int?): SpotifyTrack {
        if (cadence == null) {
            // No cadence specified, just get the next unused track in round-robin
            return getNextTrackRoundRobin(null)
        }
        
        // Get sorted tracks for this cadence
        val sortedTracks = getSortedTracksForCadence(cadence)
        
        if (sortedTracks.isEmpty()) {
            Log.w(TAG, "⚠️ No tracks available for cadence $cadence")
            return currentTrack
        }
        
        // Get current index for this cadence
        var currentIndex = cadenceIndices[cadence] ?: 0
        
        // Find the next track that:
        // 1. Is not the current track
        // 2. Is not already used in another position (avoid duplication)
        var attempts = 0
        var nextTrack = sortedTracks[currentIndex]
        
        while ((nextTrack.id == currentTrack.id || nextTrack.id in playlistTracks) && attempts < sortedTracks.size) {
            currentIndex = (currentIndex + 1) % sortedTracks.size
            nextTrack = sortedTracks[currentIndex]
            attempts++
            
            if (attempts > 0 && nextTrack.id in playlistTracks) {
                Log.d(TAG, "   ⏭️ Skipping ${nextTrack.name} (already in playlist)")
            }
        }
        
        // Move to next index for round-robin
        cadenceIndices[cadence] = (currentIndex + 1) % sortedTracks.size
        
        // Track usage
        usageCount[nextTrack.id] = (usageCount[nextTrack.id] ?: 0) + 1
        
        Log.d(TAG, "🔄 Substituting '${currentTrack.name}' with '${nextTrack.name}' for cadence $cadence")
        logTrackMatch(nextTrack, cadence)
        
        return nextTrack
    }
    
    /**
     * Sort all tracks by BPM match quality for a specific cadence
     * Considers multiples: 1x, 2x, 3x, 4x
     */
    private fun getSortedTracksForCadence(cadence: Int): List<SpotifyTrack> {
        // Return cached result if available
        cadenceSortCache[cadence]?.let { return it }
        
        Log.d(TAG, "🔍 Sorting ${allTracks.size} tracks for cadence $cadence RPM...")
        
        val sorted = allTracks.sortedBy { track ->
            calculateBpmMatchScore(track, cadence)
        }
        
        // Log top 5 matches
        Log.d(TAG, "   Top 5 matches for $cadence RPM:")
        sorted.take(5).forEachIndexed { idx, track ->
            Log.d(TAG, "   ${idx + 1}. ${track.name} - ${trackFeatures[track.id]?.bpm ?: -1} BPM")
        }
        
        // Cache for future use
        cadenceSortCache[cadence] = sorted
        return sorted
    }
    
    /**
     * Calculate BPM match score (lower is better)
     * Prioritizes exact 1x matches, then 2x/3x/4x multiples
     */
    private fun calculateBpmMatchScore(track: SpotifyTrack, targetCadence: Int): Int {
        val features = trackFeatures[track.id]
        
        if (features == null || features.bpm < 0) {
            return Int.MAX_VALUE / 2  // Penalize missing BPM
        }
        
        val tolerance = (targetCadence * 25) / 100  // 25% tolerance
        val multipliers = listOf(1, 2, 3, 4)
        var bestDistance = Int.MAX_VALUE
        var bestMultiplier = -1
        
        // Find best match among multiples
        // All multiples are treated equally: distance = abs(actual - target)
        // 150 BPM for 75 RPM (2x) scores same as 75 BPM for 75 RPM (1x)
        for (multiplier in multipliers) {
            val targetBpm = targetCadence * multiplier
            val matchTolerance = tolerance * multiplier
            
            if (features.bpm in (targetBpm - matchTolerance)..(targetBpm + matchTolerance)) {
                // Match found! Score is pure distance, no multiplier penalty
                val distance = abs(features.bpm - targetBpm)
                
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestMultiplier = multiplier
                }
            }
        }
        
        // If found a multiple match, return its score
        if (bestMultiplier > 0) {
            return bestDistance
        }
        
        // No multiple match - penalize heavily as fallback (lower score for non-matches)
        return when {
            features.bpm in (targetCadence * 0.75).toInt()..(targetCadence * 1.25).toInt() ->
                abs(features.bpm - targetCadence) + 30000  // Increased penalty
            else -> abs(features.bpm - targetCadence) + 35000  // Higher penalty
        }
    }
    
    /**
     * Get next track in round-robin fashion (no cadence-specific sorting)
     */
    private fun getNextTrackRoundRobin(@Suppress("UNUSED_PARAMETER") excludeCadence: Int?): SpotifyTrack {
        var index = cadenceIndices[-1] ?: 0  // Use -1 as key for general round-robin
        val nextTrack = allTracks[index % allTracks.size]
        cadenceIndices[-1] = (index + 1) % allTracks.size
        return nextTrack
    }
    
    /**
     * Log detailed information about track BPM match
     */
    private fun logTrackMatch(track: SpotifyTrack, cadence: Int) {
        val features = trackFeatures[track.id] ?: return
        val bpm = features.bpm
        
        val multipliers = listOf(1, 2, 3, 4)
        val tolerance = (cadence * 25) / 100
        
        for (multiplier in multipliers) {
            val targetBpm = cadence * multiplier
            val matchTolerance = tolerance * multiplier
            
            if (bpm in (targetBpm - matchTolerance)..(targetBpm + matchTolerance)) {
                Log.d(TAG, "   ✅ ${multiplier}x match: $bpm BPM vs target ${targetBpm} (tolerance ±${matchTolerance})")
                return
            }
        }
        
        Log.d(TAG, "   📊 No multiple match: $bpm BPM vs cadence $cadence")
    }
    
    /**
     * Get usage statistics
     */
    fun getStats(): String {
        val totalUsages = usageCount.values.sum()
        val mostUsed = usageCount.maxByOrNull { it.value }?.let { "${it.key}: ${it.value}x" } ?: "none"
        return "Total substitutions: $totalUsages, Most used: $mostUsed"
    }
    
    /**
     * Reset tracking for a new playlist
     */
    fun reset() {
        cadenceSortCache.clear()
        cadenceIndices.clear()
        usageCount.clear()
        Log.d(TAG, "🔄 Substitution manager reset")
    }
}

