package se.fpq.remote.test

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "ReccoBeatsService"
private const val API_BASE_URL = "https://api.reccobeats.com/v1"
private const val PREFS_NAME = "reccobeats_cache"
private const val PREFS_KEY_PREFIX = "bpm_"

data class AudioFeatures(
    val id: String,
    val tempo: Float?,
    val energy: Float?,
    val danceability: Float?,
    val valence: Float?,
    val acousticness: Float?
)

class ReccoBeatsService(private val context: Context? = null) {
    
    // In-memory cache for audio features (track ID -> features)
    private val cache = ConcurrentHashMap<String, AudioFeatures?>()
    
    // Persistent storage
    private val prefs: SharedPreferences? = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    init {
        // Load cache from SharedPreferences on initialization (only if context is available)
        if (prefs != null) {
            try {
                val cachedBpms = prefs.all
                var loadedCount = 0
                for ((key, value) in cachedBpms) {
                    if (key.startsWith(PREFS_KEY_PREFIX) && value is Int) {
                        val trackId = key.removePrefix(PREFS_KEY_PREFIX)
                        // Store as a marker that this track was cached (we only store BPM, not full features)
                        cache[trackId] = null  // Mark as cached to avoid re-fetching
                        loadedCount++
                    }
                }
                if (loadedCount > 0) {
                    Log.d(TAG, "✅ Loaded $loadedCount cached BPM values from storage")
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Failed to load cache from storage: ${e.message}")
            }
        } else {
            Log.d(TAG, "ℹ️ Context not available, cache persistence disabled")
        }
    }
    
    /**
     * Get BPM for a specific track from persistent storage
     */
    private fun getBpmFromStorage(trackId: String): Int? {
        if (prefs == null) return null
        val key = PREFS_KEY_PREFIX + trackId
        val bpm = prefs.getInt(key, -1)
        return if (bpm > 0) bpm else null
    }
    
    companion object {
        private var instance: ReccoBeatsService? = null
        
        fun getInstance(context: Context? = null): ReccoBeatsService {
            if (instance == null) {
                instance = ReccoBeatsService(context)
            }
            return instance!!
        }
    }
    
    /**
     * Save BPM to persistent storage
     */
    private fun saveBpmToStorage(trackId: String, bpm: Int?) {
        if (prefs == null) return
        
        val key = PREFS_KEY_PREFIX + trackId
        if (bpm != null && bpm > 0) {
            prefs.edit().putInt(key, bpm).apply()
        } else {
            prefs.edit().remove(key).apply()
        }
    }
    
    /**
     * Get audio features for multiple Spotify tracks
     * No API key required!
     * Note: ReccoBeats limits to 40 tracks per request, so we batch them
     */
    suspend fun getAudioFeatures(trackIds: List<String>): Map<String, AudioFeatures> = withContext(Dispatchers.IO) {
        try {
            if (trackIds.isEmpty()) {
                Log.d(TAG, "⚠️ No track IDs provided")
                return@withContext emptyMap()
            }

            // Check cache first (both in-memory and persistent storage)
            val cachedResults = mutableMapOf<String, AudioFeatures>()
            val uncachedIds = mutableListOf<String>()
            
            for (id in trackIds) {
                // Check in-memory cache first
                if (cache.containsKey(id)) {
                    val cached = cache[id]
                    if (cached != null) {
                        cachedResults[id] = cached
                    }
                } else {
                    // Check persistent storage
                    val storedBpm = getBpmFromStorage(id)
                    if (storedBpm != null) {
                        // Reconstruct AudioFeatures from stored BPM
                        val features = AudioFeatures(
                            id = id,
                            tempo = storedBpm.toFloat(),
                            energy = null,
                            danceability = null,
                            valence = null,
                            acousticness = null
                        )
                        cache[id] = features
                        cachedResults[id] = features
                        Log.d(TAG, "   📦 Loaded from storage: $id - $storedBpm BPM")
                    } else {
                        uncachedIds.add(id)
                    }
                }
            }
            
            if (uncachedIds.isNotEmpty()) {
                Log.d(TAG, "🔍 Fetching audio features for ${uncachedIds.size} tracks from ReccoBeats...")
                
                // Split into batches of 40 (ReccoBeats limit)
                val batchSize = 40
                val batches = uncachedIds.chunked(batchSize)
                Log.d(TAG, "   Batching ${uncachedIds.size} tracks into ${batches.size} requests (max 40 per request)")
                
                for ((batchIndex, batch) in batches.withIndex()) {
                    try {
                        // Build request URL with track IDs
                        val idsParam = batch.joinToString(",")
                        val urlString = "$API_BASE_URL/audio-features?ids=$idsParam"
                        
                        Log.d(TAG, "   [Batch ${batchIndex + 1}/${batches.size}] Fetching ${batch.size} tracks...")
                        
                        val url = URL(urlString)
                        val connection = url.openConnection() as HttpURLConnection
                        connection.requestMethod = "GET"
                        connection.setRequestProperty("Accept", "application/json")
                        connection.connectTimeout = 10000
                        connection.readTimeout = 10000
                        
                        try {
                            val responseCode = connection.responseCode
                            if (responseCode == HttpURLConnection.HTTP_OK) {
                                val response = connection.inputStream.bufferedReader().readText()
                                Log.d(TAG, "      Response received: ${response.length} bytes")
                                
                                // Response format: {"content": [...]}
                                val jsonResponse = JSONObject(response)
                                val jsonArray = jsonResponse.getJSONArray("content")
                                
                                for (i in 0 until jsonArray.length()) {
                                    val item = jsonArray.getJSONObject(i)
                                    val href = item.optString("href", null)
                                    
                                    // Extract Spotify track ID from href (e.g., "https://open.spotify.com/track/7lQ8MOhq6IN2w8EYcFNSUk")
                                    val spotifyTrackId = href?.substringAfterLast("/")
                                    
                                    if (spotifyTrackId != null && !spotifyTrackId.isEmpty()) {
                                        val tempo = item.optDouble("tempo", -1.0).takeIf { it >= 0 }?.toInt()
                                        val features = AudioFeatures(
                                            id = spotifyTrackId,
                                            tempo = tempo?.toFloat() ?: -1f,
                                            energy = item.optDouble("energy", -1.0).takeIf { it >= 0 }?.toFloat(),
                                            danceability = item.optDouble("danceability", -1.0).takeIf { it >= 0 }?.toFloat(),
                                            valence = item.optDouble("valence", -1.0).takeIf { it >= 0 }?.toFloat(),
                                            acousticness = item.optDouble("acousticness", -1.0).takeIf { it >= 0 }?.toFloat()
                                        )
                                        cache[spotifyTrackId] = features
                                        cachedResults[spotifyTrackId] = features
                                        // Save to persistent storage
                                        if (tempo != null) {
                                            saveBpmToStorage(spotifyTrackId, tempo)
                                        }
                                        Log.d(TAG, "      ✅ $spotifyTrackId: ${tempo ?: "no data"} BPM")
                                    }
                                }
                                Log.d(TAG, "   ✅ Batch ${batchIndex + 1} complete: ${jsonArray.length()} tracks")
                            } else {
                                Log.e(TAG, "   ❌ API error: $responseCode")
                                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                                Log.e(TAG, "      Error: $errorBody")
                            }
                        } finally {
                            connection.disconnect()
                        }
                        
                        // Small delay between batches to avoid rate limiting
                        Thread.sleep(100)
                    } catch (e: Exception) {
                        Log.e(TAG, "   ❌ Batch ${batchIndex + 1} failed: ${e.message}")
                    }
                }
                
                Log.d(TAG, "✅ Fetched audio features for ${cachedResults.size} tracks total")
            } else {
                Log.d(TAG, "✅ All ${trackIds.size} tracks found in cache")
            }
            
            return@withContext cachedResults
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception: ${e.message}", e)
            return@withContext emptyMap()
        }
    }
    
    /**
     * Get cache statistics
     */
    fun getCacheStats(): String {
        val cachedCount = cache.count { it.value != null }
        val totalCount = cache.size
        return "ReccoBeats Cache: $cachedCount/$totalCount tracks"
    }
    
    /**
     * Clear the cache
     */
    fun clearCache() {
        cache.clear()
        Log.d(TAG, "🗑️ Cache cleared")
    }
}

