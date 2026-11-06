package se.fpq.remote.test

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import org.json.JSONArray

private const val TAG = "SpotifyApiService"

/**
 * Service for making Spotify Web API calls
 */
class SpotifyApiService(private val accessToken: String) {
    
    private val apiBaseUrl = "https://api.spotify.com/v1"

    /**
     * Fetch user's saved tracks from Spotify (with pagination to get ALL tracks)
     */
    suspend fun getUserSavedTracks(limit: Int = 50): Result<List<SpotifyTrack>> = withContext(Dispatchers.IO) {
        try {
            val tracks = mutableListOf<SpotifyTrack>()
            var offset = 0
            var total = Int.MAX_VALUE  // Will be updated from first response
            
            // Keep fetching until we've retrieved all tracks
            while (offset < total) {
                val url = "$apiBaseUrl/me/tracks?limit=$limit&offset=$offset"
                Log.d(TAG, "📖 Fetching saved tracks: offset=$offset, limit=$limit")
                
                val response = makeGetRequest(url)
                
                // Get total count from first response
                if (offset == 0) {
                    total = response.optInt("total", 0)
                    Log.d(TAG, "📊 Total saved tracks available: $total")
                }
                
                val items = response.getJSONArray("items")
                
                for (i in 0 until items.length()) {
                    val itemObj = items.getJSONObject(i)
                    val trackObj = itemObj.optJSONObject("track")
                    
                    // Skip if track is null (shouldn't happen for saved tracks, but be safe)
                    if (trackObj == null || trackObj.isNull("id")) {
                        Log.d(TAG, "⚠️ Skipping null/deleted saved track")
                        continue
                    }
                    
                    val track = parseTrack(trackObj)
                    tracks.add(track)
                }
                
                offset += limit
                
                // Log progress
                val progress = Math.min(offset, total)
                Log.d(TAG, "📈 Progress: $progress / $total tracks fetched")
            }
            
            Log.d(TAG, "✅ Fetched ${tracks.size} saved tracks (complete)")
            Result.success(tracks)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to fetch saved tracks", e)
            Result.failure(e)
        }
    }

    /**
     * Fetch user's playlists
     */
    suspend fun getUserPlaylists(): Result<List<SpotifyPlaylist>> = withContext(Dispatchers.IO) {
        try {
            val playlists = mutableListOf<SpotifyPlaylist>()
            var offset = 0
            var total = Int.MAX_VALUE
            
            while (offset < total) {
                val url = "$apiBaseUrl/me/playlists?limit=50&offset=$offset"
                Log.d(TAG, "📖 Fetching playlists: offset=$offset, limit=50")
                
                val response = makeGetRequest(url)
                
                if (offset == 0) {
                    total = response.optInt("total", 0)
                    Log.d(TAG, "📊 Total playlists available: $total")
                }
                
                val items = response.getJSONArray("items")
                Log.d(TAG, "   Received ${items.length()} playlists in this batch")
                
                for (i in 0 until items.length()) {
                    val playlistObj = items.getJSONObject(i)
                    val playlist = SpotifyPlaylist(
                        id = playlistObj.getString("id"),
                        name = playlistObj.getString("name"),
                        uri = playlistObj.getString("uri"),
                        trackCount = playlistObj.getJSONObject("tracks").getInt("total")
                    )
                    playlists.add(playlist)
                    Log.d(TAG, "     📋 ${playlist.name} (${playlist.trackCount} tracks)")
                }
                
                offset += 50
                
                if (items.length() == 0) {
                    Log.d(TAG, "   No more playlists to fetch")
                    break
                }
            }
            
            Log.d(TAG, "✅ Fetched ${playlists.size} playlists total")
            Result.success(playlists)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to fetch playlists: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Search for a playlist by name
     */
    suspend fun searchPlaylist(query: String): Result<List<SpotifyPlaylist>> = withContext(Dispatchers.IO) {
        try {
            val playlists = mutableListOf<SpotifyPlaylist>()
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "$apiBaseUrl/search?q=$encodedQuery&type=playlist&limit=20"
            
            Log.d(TAG, "🔍 Searching for playlist: '$query'")
            val response = makeGetRequest(url)
            
            val items = response.getJSONObject("playlists").getJSONArray("items")
            Log.d(TAG, "   Found ${items.length()} playlist results")
            
            for (i in 0 until items.length()) {
                val playlistObj = items.getJSONObject(i)
                val playlist = SpotifyPlaylist(
                    id = playlistObj.getString("id"),
                    name = playlistObj.getString("name"),
                    uri = playlistObj.getString("uri"),
                    trackCount = playlistObj.getJSONObject("tracks").getInt("total")
                )
                playlists.add(playlist)
                Log.d(TAG, "     📋 ${playlist.name} (${playlist.trackCount} tracks)")
            }
            
            Log.d(TAG, "✅ Found ${playlists.size} playlists matching '$query'")
            Result.success(playlists)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to search playlists: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Fetch tracks from a specific playlist
     */
    suspend fun getPlaylistTracks(playlistId: String): Result<List<SpotifyTrack>> = withContext(Dispatchers.IO) {
        try {
            val tracks = mutableListOf<SpotifyTrack>()
            var offset = 0
            var total = Int.MAX_VALUE
            
            while (offset < total) {
                val url = "$apiBaseUrl/playlists/$playlistId/tracks?limit=50&offset=$offset&market=from_token"
                Log.d(TAG, "📖 Fetching playlist tracks: offset=$offset")
                
                val response = makeGetRequest(url)
                
                if (offset == 0) {
                    total = response.optInt("total", 0)
                    Log.d(TAG, "📊 Total playlist tracks available: $total")
                }
                
                val items = response.getJSONArray("items")
                var skippedCount = 0
                for (i in 0 until items.length()) {
                    val itemObj = items.getJSONObject(i)
                    val trackObj = itemObj.optJSONObject("track")
                    
                    // Skip if track is null (deleted tracks can appear in playlists)
                    if (trackObj == null || trackObj.isNull("id")) {
                        Log.d(TAG, "⚠️ Skipping null/deleted track in playlist")
                        skippedCount++
                        continue
                    }
                    
                    val trackName = trackObj.optString("name", "Unknown")
                    val trackId = trackObj.optString("id", "Unknown")
                    
                    // Log details for Mercedes Benz specifically to debug
                    val isMercedesBenz = trackId == "1i2MuFtYQeo05W1pvYhRl6" || trackName.contains("Mercedes Benz", ignoreCase = true)
                    if (isMercedesBenz) {
                        Log.d(TAG, "🔍 DEBUG: Found Mercedes Benz track")
                        Log.d(TAG, "   Item keys: ${itemObj.keys().asSequence().toList()}")
                        Log.d(TAG, "   Track keys: ${trackObj.keys().asSequence().toList()}")
                        Log.d(TAG, "   Item JSON: ${itemObj.toString().take(500)}")
                        Log.d(TAG, "   Track JSON: ${trackObj.toString().take(500)}")
                    }
                    
                    // Check if track is playable
                    // is_playable can be on the item object or the track object
                    // Check both - if either explicitly says false, skip the track
                    val itemHasIsPlayable = itemObj.has("is_playable")
                    val trackHasIsPlayable = trackObj.has("is_playable")
                    val itemIsPlayable = if (itemHasIsPlayable) itemObj.getBoolean("is_playable") else true
                    val trackIsPlayable = if (trackHasIsPlayable) trackObj.getBoolean("is_playable") else true
                    
                    if (isMercedesBenz) {
                        Log.d(TAG, "   item.has('is_playable')=$itemHasIsPlayable, item.is_playable=$itemIsPlayable")
                        Log.d(TAG, "   track.has('is_playable')=$trackHasIsPlayable, track.is_playable=$trackIsPlayable")
                    }
                    
                    // Track is unplayable if either explicitly says false
                    val isPlayable = itemIsPlayable && trackIsPlayable
                    
                    if (!isPlayable) {
                        Log.w(TAG, "⚠️ Skipping unplayable track: $trackName (ID: $trackId)")
                        Log.w(TAG, "   item.has('is_playable')=$itemHasIsPlayable, item.is_playable=$itemIsPlayable")
                        Log.w(TAG, "   track.has('is_playable')=$trackHasIsPlayable, track.is_playable=$trackIsPlayable")
                        skippedCount++
                        continue
                    }
                    
                    // Check for restrictions - this is a reliable indicator
                    if (trackObj.has("restrictions")) {
                        val restrictions = trackObj.optJSONObject("restrictions")
                        val reason = restrictions?.optString("reason", null)
                        if (reason != null) {
                            Log.w(TAG, "⚠️ Skipping restricted track: $trackName (ID: $trackId) - reason: $reason")
                            if (isMercedesBenz) {
                                Log.d(TAG, "   Restrictions object: ${restrictions.toString()}")
                            }
                            skippedCount++
                            continue
                        }
                    }
                    
                    // Check if track is_local (local files can't be played via API)
                    val isLocal = trackObj.optBoolean("is_local", false)
                    if (isLocal) {
                        Log.w(TAG, "⚠️ SKIP: $trackName | Reason: Local track")
                        skippedCount++
                        continue
                    }
                    
                    // Check if track is an episode (podcasts) - these might not play the same way
                    val isEpisode = trackObj.optBoolean("episode", false)
                    if (isEpisode) {
                        Log.w(TAG, "⚠️ SKIP: $trackName | Reason: Episode (podcast)")
                        skippedCount++
                        continue
                    }
                    
                    val track = parseTrack(trackObj)
                    tracks.add(track)
                }
                
                if (skippedCount > 0) {
                    Log.d(TAG, "📊 Filtered out $skippedCount unplayable/restricted tracks from playlist")
                }
                
                offset += 50
            }
            
            Log.d(TAG, "✅ Fetched ${tracks.size} tracks from playlist")
            Result.success(tracks)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to fetch playlist tracks", e)
            Result.failure(e)
        }
    }

    /**
     * Fetch audio features for multiple tracks
     */
    suspend fun getAudioFeatures(trackIds: List<String>, tracks: List<SpotifyTrack> = emptyList()): Result<Map<String, SpotifyAudioFeatures>> = withContext(Dispatchers.IO) {
        try {
            val features = mutableMapOf<String, SpotifyAudioFeatures>()
            
            // Note: Spotify's audio-features endpoint is deprecated and returns 403 for new apps (as of Nov 27, 2024)
            // See: https://developer.spotify.com/blog/2024-11-27-changes-to-the-web-api
            // Instead, we use default values and can enrich with GetSongKEY API
            Log.d(TAG, "ℹ️ Spotify audio-features endpoint deprecated (Nov 27, 2024). Using default BPM values.")
            
            // For each track, create audio features with default/estimated values
                tracks.forEach { track ->
                    val audioFeatures = SpotifyAudioFeatures(
                        id = track.id,
                        bpm = -1,  // -1 = no BPM data available (can be enriched via ReccoBeats API)
                        energy = 0.5f,
                        danceability = 0.5f,
                        valence = 0.5f
                    )
                    features[track.id] = audioFeatures
                }
            
            Log.d(TAG, "✅ Created audio features for ${features.size} tracks (using defaults)")
            Result.success(features)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to create audio features", e)
            Result.failure(e)
        }
    }

    /**
     * Make a GET request to Spotify API
     */
    private fun makeGetRequest(urlString: String): JSONObject {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        
        try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            // Don't set Content-Type for GET requests
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            
            Log.d(TAG, "🌐 HTTP Request:")
            Log.d(TAG, "   URL: $urlString")
            Log.d(TAG, "   Method: GET")
            Log.d(TAG, "   Full Auth header: Bearer $accessToken")
            Log.d(TAG, "   Auth header (truncated): Bearer ${accessToken?.take(20)}...")
            
            val responseCode = connection.responseCode
            Log.d(TAG, "📊 Response: $responseCode")
            Log.d(TAG, "   Headers: ${connection.headerFields}")
            
            if (responseCode != 200) {
                val error = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                Log.e(TAG, "   Error details: $error")
                throw Exception("API Error: $responseCode - $error")
            }
            
            val response = connection.inputStream.bufferedReader().readText()
            return JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Parse a track from JSON
     */
    private fun parseTrack(trackObj: JSONObject): SpotifyTrack {
        val artist = trackObj.getJSONArray("artists").getJSONObject(0)
        val album = trackObj.getJSONObject("album")
        
        return SpotifyTrack(
            id = trackObj.getString("id"),
            name = trackObj.getString("name"),
            artist = SpotifyArtist(
                id = artist.getString("id"),
                name = artist.getString("name"),
                uri = artist.getString("uri")
            ),
            album = SpotifyAlbum(
                id = album.getString("id"),
                name = album.getString("name"),
                uri = album.getString("uri"),
                images = emptyList()
            ),
            durationMs = trackObj.getInt("duration_ms"),
            uri = trackObj.getString("uri")
        )
    }
}

