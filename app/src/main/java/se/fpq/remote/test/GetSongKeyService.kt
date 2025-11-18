package se.fpq.remote.test

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "GetSongKeyService"
// IMPORTANT: Get your API key from https://getsongkey.com/api
private const val API_KEY = "YOUR_API_KEY_HERE"  // TODO: Replace with your actual GetSongKEY API key
private const val API_BASE_URL = "https://api.getsong.co"  // Correct endpoint

data class SongKeyData(
    val id: String,
    val bpm: Int?,
    val key: String?,
    val camelot: String?
)

class GetSongKeyService(private val context: Context) {
    
    // Simple in-memory cache for BPM data (track ID -> BPM)
    private val bpmCache = mutableMapOf<String, Int?>()
    
    /**
     * Get BPM for a track by searching by artist and song name
     * Results are cached in memory
     */
    suspend fun getBPMForTrack(artistName: String, trackName: String): Int? = withContext(Dispatchers.IO) {
        try {
            // Create a cache key
            val cacheKey = "$artistName|$trackName"
            
            // Check if we have it cached
            if (bpmCache.containsKey(cacheKey)) {
                val cachedBpm = bpmCache[cacheKey]
                Log.d(TAG, "📦 Cache hit for '$trackName' by $artistName: $cachedBpm BPM")
                return@withContext cachedBpm
            }
            
            Log.d(TAG, "🔍 Looking up '$trackName' by $artistName on GetSongKEY...")
            
            // Check if API key is configured
            if (API_KEY == "YOUR_API_KEY_HERE") {
                Log.e(TAG, "   ❌ GetSongKEY API key not configured. Register at https://getsongkey.com/api")
                return@withContext null
            }
            
            // Build the search URL with API key
            val searchQuery = "$trackName $artistName"
            val encodedQuery = java.net.URLEncoder.encode(searchQuery, "UTF-8")
            val url = URL("$API_BASE_URL/api/v1/artist-tracks?api_key=$API_KEY&q=$encodedQuery")
            
            Log.d(TAG, "   URL: (API key hidden)")
            
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            
            try {
                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().readText()
                    val jsonResponse = JSONObject(response)
                    
                    Log.d(TAG, "   Response: ${response.take(200)}...")
                    
                    // Parse the response to get BPM
                    if (jsonResponse.has("songs") && jsonResponse.getJSONArray("songs").length() > 0) {
                        val firstSong = jsonResponse.getJSONArray("songs").getJSONObject(0)
                        val bpm = firstSong.optInt("bpm", -1).takeIf { it > 0 }
                        val key = firstSong.optString("key", null)
                        val camelot = firstSong.optString("camelot", null)
                        
                        Log.d(TAG, "   ✅ Found: BPM=$bpm, Key=$key, Camelot=$camelot")
                        
                        // Cache the result
                        bpmCache[cacheKey] = bpm
                        
                        return@withContext bpm
                    } else {
                        Log.w(TAG, "   ⚠️ No songs found in response")
                        bpmCache[cacheKey] = null
                        return@withContext null
                    }
                } else {
                    Log.e(TAG, "   ❌ API error: ${connection.responseCode}")
                    val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                    Log.e(TAG, "   Error: $errorBody")
                    return@withContext null
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "   ❌ Exception: ${e.message}", e)
            return@withContext null
        }
    }
    
    /**
     * Get cached BPM without making an API call
     */
    fun getCachedBPM(artistName: String, trackName: String): Int? {
        val cacheKey = "$artistName|$trackName"
        return bpmCache[cacheKey]
    }
    
    /**
     * Manually cache a BPM value
     */
    fun cacheBPM(artistName: String, trackName: String, bpm: Int?) {
        val cacheKey = "$artistName|$trackName"
        bpmCache[cacheKey] = bpm
        Log.d(TAG, "💾 Cached BPM for '$trackName' by $artistName: $bpm")
    }
    
    /**
     * Get cache statistics
     */
    fun getCacheStats(): String {
        return "Cache size: ${bpmCache.size} entries"
    }
}

