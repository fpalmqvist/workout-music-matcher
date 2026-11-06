package se.fpq.remote.test

import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : FragmentActivity() {
    companion object {
        private const val TAG = "WizardApp"
        private const val CLIENT_ID = "5da349f9ebfa420fa5820ee963cefef6"
        private const val REDIRECT_URI = "se.fpq.remote.test://callback"
    }

    var spotifyAppRemote: SpotifyAppRemote? = null
    var isAppRemoteConnected = false
    var isWebApiAuthenticated = false
    private var pendingAuthCallback: ((Boolean) -> Unit)? = null
    
    lateinit var authManager: AuthManager
    private var cachedApiService: SpotifyApiService? = null
    
    var currentWorkout: Workout? = null
    private var _currentPlaylist: List<WorkoutTrack> = emptyList()
    var currentPlaylist: List<WorkoutTrack>
        get() = _currentPlaylist
        set(value) {
            _currentPlaylist = value
            // Auto-save playlist to SharedPreferences
            savePlaylistToPrefs(value)
        }
    var currentGeneratedPlaylist: GeneratedPlaylist? = null
    var shouldResumePlayback = false  // Flag to resume playback after reconnection
    var onConnectionRestored: (() -> Unit)? = null  // Callback to resync playback after reconnection
    var wasDisconnectedBeforeResume = false  // Track if we were actually disconnected when Fragment resumed
    var isWorkoutPlaying = false  // Track if workout playback is active
    
    private val sharedPref by lazy {
        getSharedPreferences("spotify_remote_prefs", android.content.Context.MODE_PRIVATE)
    }
    
    var selectedPlaylistId: String?
        get() = sharedPref.getString("selected_playlist_id", null)
        set(value) {
            sharedPref.edit().putString("selected_playlist_id", value).apply()
            Log.d(TAG, "💾 Saved selected playlist: $value")
        }
    
    private val authLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        Log.d(TAG, "Auth result: resultCode=${result.resultCode}, data=${result.data}")
        
        // Extract the token from the response
        val response = com.spotify.sdk.android.auth.AuthorizationClient.getResponse(result.resultCode, result.data)
        Log.d(TAG, "Auth response type: ${response.type}")
        
        when (response.type) {
            com.spotify.sdk.android.auth.AuthorizationResponse.Type.TOKEN -> {
                AuthManager.setAccessToken(response.accessToken)
                Log.d(TAG, "✅ Got access token: ${response.accessToken?.take(20)}...")
                isWebApiAuthenticated = true
                val token = response.accessToken
                if (token != null) {
                    cachedApiService = SpotifyApiService(token)
                    Log.d(TAG, "✅ Web API service initialized")
                }
            }
            com.spotify.sdk.android.auth.AuthorizationResponse.Type.ERROR -> {
                Log.e(TAG, "❌ Auth error: ${response.error}")
                isWebApiAuthenticated = false
            }
            else -> {
                Log.w(TAG, "⚠️ Auth cancelled or unknown")
                isWebApiAuthenticated = false
            }
        }
        
        pendingAuthCallback?.invoke(isWebApiAuthenticated)
        pendingAuthCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        authManager = AuthManager(this)
        
        // Restore state if available
        if (savedInstanceState != null) {
            val bundle = savedInstanceState
            isWorkoutPlaying = bundle.getBoolean("isWorkoutPlaying", false)
            shouldResumePlayback = bundle.getBoolean("shouldResumePlayback", false)
            wasDisconnectedBeforeResume = bundle.getBoolean("wasDisconnectedBeforeResume", false)
            
            // Restore workout from saved workout files if available
            val savedWorkoutName = bundle.getString("currentWorkoutName")
            if (savedWorkoutName != null && currentWorkout == null) {
                restoreWorkoutFromFile(savedWorkoutName)
            }
            
            // Restore playlist from SharedPreferences
            restorePlaylistFromPrefs()
            
            Log.d(TAG, "🔄 Restored MainActivity state: isWorkoutPlaying=$isWorkoutPlaying, workoutName=$savedWorkoutName, playlistSize=${currentPlaylist.size}")
        } else {
            // No saved instance state, but check SharedPreferences for workout name
            // (in case of process death and recreation)
            val savedWorkoutName = sharedPref.getString("current_workout_name", null)
            if (savedWorkoutName != null && currentWorkout == null) {
                Log.d(TAG, "🔄 Restoring workout from SharedPreferences: $savedWorkoutName")
                restoreWorkoutFromFile(savedWorkoutName)
            }
        }
        
        // Connect to Spotify App Remote on startup
        connectToSpotifyAppRemote()
        
        // Authenticate with Web API on startup
        authenticateWithWebApi()
        
        // Show first fragment if this is first load
        // Note: Fragment back stack should preserve fragments automatically
        if (savedInstanceState == null) {
            showWorkoutSelectionFragment()
        }
    }
    
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        
        outState.putBoolean("isWorkoutPlaying", isWorkoutPlaying)
        outState.putBoolean("shouldResumePlayback", shouldResumePlayback)
        outState.putBoolean("wasDisconnectedBeforeResume", wasDisconnectedBeforeResume)
        
        // Save workout name/id for reference (fragments can reload if needed)
        currentWorkout?.let { workout ->
            outState.putString("currentWorkoutId", workout.id)
            outState.putString("currentWorkoutName", workout.name)
            // Also save to SharedPreferences for persistence across process death
            sharedPref.edit().putString("current_workout_name", workout.name).apply()
            Log.d(TAG, "💾 Saved workout name to prefs: ${workout.name}")
        }
        
        Log.d(TAG, "💾 Saved MainActivity state: isWorkoutPlaying=$isWorkoutPlaying, workoutId=${currentWorkout?.id}")
    }
    
    /**
     * Restore workout from saved .zwo file in cache directory
     * Searches all workout files and matches by the workout name inside the XML
     */
    private fun restoreWorkoutFromFile(workoutName: String) {
        try {
            val workoutFiles = cacheDir.listFiles { file ->
                file.name.endsWith(".zwo")
            } ?: emptyArray()
            
            Log.d(TAG, "🔍 Searching for workout: '$workoutName' in ${workoutFiles.size} files")
            
            // Try to find by filename first (for backwards compatibility)
            var workoutFile = workoutFiles.find { file ->
                file.nameWithoutExtension == workoutName
            }
            
            // If not found by filename, parse all files and match by workout name inside XML
            if (workoutFile == null) {
                Log.d(TAG, "   File not found by filename, searching by workout name in XML...")
                for (file in workoutFiles) {
                    try {
                        val result = ZwoWorkoutParser.parse(file.inputStream())
                        result.onSuccess { workout ->
                            if (workout.name == workoutName) {
                                workoutFile = file
                                Log.d(TAG, "   ✅ Found matching workout in file: ${file.name}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "   ⚠️ Error parsing ${file.name}: ${e.message}")
                    }
                }
            }
            
            // Use a local copy to avoid smart cast issues
            val fileToUse = workoutFile
            if (fileToUse != null) {
                val result = ZwoWorkoutParser.parse(fileToUse.inputStream())
                result.onSuccess { workout ->
                    currentWorkout = workout
                    Log.d(TAG, "✅ Restored workout from file: ${fileToUse.name} (name: ${workout.name})")
                }.onFailure { error ->
                    Log.e(TAG, "❌ Failed to restore workout: ${error.message}")
                }
            } else {
                Log.w(TAG, "⚠️ Workout file not found for workout name: '$workoutName'")
                Log.d(TAG, "   Available files: ${workoutFiles.map { it.name }.joinToString(", ")}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error restoring workout: ${e.message}", e)
        }
    }
    
    /**
     * Save playlist to SharedPreferences as JSON
     */
    private fun savePlaylistToPrefs(playlist: List<WorkoutTrack>) {
        try {
            val jsonArray = JSONArray()
            playlist.forEach { track ->
                val jsonObject = JSONObject().apply {
                    put("trackUri", track.trackUri)
                    put("startTimeMs", track.startTimeMs)
                    put("endTimeMs", track.endTimeMs)
                    put("blockName", track.blockName)
                    track.targetCadence?.let { put("targetCadence", it) }
                    track.bpm?.let { put("bpm", it) }
                }
                jsonArray.put(jsonObject)
            }
            sharedPref.edit().putString("current_playlist", jsonArray.toString()).apply()
            Log.d(TAG, "💾 Saved playlist to prefs: ${playlist.size} tracks")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error saving playlist: ${e.message}", e)
        }
    }
    
    /**
     * Restore playlist from SharedPreferences
     */
    private fun restorePlaylistFromPrefs() {
        try {
            val playlistJson = sharedPref.getString("current_playlist", null)
            if (playlistJson != null && playlistJson.isNotEmpty()) {
                val jsonArray = JSONArray(playlistJson)
                val restoredPlaylist = mutableListOf<WorkoutTrack>()
                
                for (i in 0 until jsonArray.length()) {
                    val jsonObject = jsonArray.getJSONObject(i)
                    val track = WorkoutTrack(
                        jsonObject.getString("trackUri"),
                        jsonObject.getInt("startTimeMs"),
                        jsonObject.getInt("endTimeMs"),
                        jsonObject.getString("blockName"),
                        if (jsonObject.has("targetCadence") && !jsonObject.isNull("targetCadence")) {
                            jsonObject.getInt("targetCadence")
                        } else null,
                        if (jsonObject.has("bpm") && !jsonObject.isNull("bpm")) {
                            jsonObject.getInt("bpm")
                        } else null
                    )
                    restoredPlaylist.add(track)
                }
                
                // Only restore if we don't already have a playlist (to avoid overwriting)
                if (_currentPlaylist.isEmpty() && restoredPlaylist.isNotEmpty()) {
                    // Set directly to backing field to avoid triggering save
                    _currentPlaylist = restoredPlaylist
                    Log.d(TAG, "✅ Restored playlist from prefs: ${restoredPlaylist.size} tracks")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error restoring playlist: ${e.message}", e)
        }
    }

    override fun onPause() {
        super.onPause()
        // Only pause playback if NOT in a workout
        // During workouts, we want continuous playback even if screen dims or app goes background
        if (!isWorkoutPlaying) {
            Log.d(TAG, "⏸️ Pausing playback (app going to background)")
            spotifyAppRemote?.playerApi?.pause()
        } else {
            Log.d(TAG, "🏃 Workout playing - keeping Spotify playback active in background")
        }
        // Disable screen on flag when leaving (OS will handle screen based on settings)
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onResume() {
        super.onResume()
        // Keep screen on during workout playback
        if (isWorkoutPlaying) {
            Log.d(TAG, "💡 Keeping screen on during workout")
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        // Reconnect to Spotify App Remote when returning to foreground
        if (!isAppRemoteConnected) {
            Log.d(TAG, "🔄 App resumed - reconnecting to Spotify...")
            connectToSpotifyAppRemote()
        }
    }
    
    /**
     * Resume playback after reconnection
     * Called by PlaybackFragment when it detects we're back in foreground
     */
    fun resumeWorkoutPlayback() {
        Log.d(TAG, "▶️ Resuming workout playback...")
        spotifyAppRemote?.playerApi?.resume()
    }

    override fun onStop() {
        super.onStop()
        // Only disconnect if NOT in a workout
        // Keep connection alive during workouts so transitions work in background
        if (!isWorkoutPlaying && spotifyAppRemote != null) {
            SpotifyAppRemote.disconnect(spotifyAppRemote)
            isAppRemoteConnected = false
            Log.d(TAG, "👋 Disconnected from Spotify App Remote")
        } else if (isWorkoutPlaying) {
            Log.d(TAG, "🏃 Workout in progress - keeping Spotify connection alive")
        }
    }

    fun connectToSpotifyAppRemote() {
        if (isAppRemoteConnected) {
            Log.d(TAG, "Already connected to Spotify App Remote")
            return
        }
        
        Log.d(TAG, "🔗 Connecting to Spotify App Remote...")
        
        val connectionParams = ConnectionParams.Builder(CLIENT_ID)
            .setRedirectUri(REDIRECT_URI)
            .showAuthView(false)
            .build()

        SpotifyAppRemote.connect(this, connectionParams, object : Connector.ConnectionListener {
                override fun onConnected(spotifyAppRemote: SpotifyAppRemote) {
                    this@MainActivity.spotifyAppRemote = spotifyAppRemote
                    this@MainActivity.isAppRemoteConnected = true
                    Log.d(TAG, "✅ Connected to Spotify App Remote!")
                    
                    // If we need to resume playback after reconnection AND were actually disconnected, do it now
                    if (shouldResumePlayback && wasDisconnectedBeforeResume) {
                        Log.d(TAG, "▶️ Resuming playback after reconnection...")
                        // Call the callback to resync playback to the correct track
                        onConnectionRestored?.invoke()
                        shouldResumePlayback = false
                        wasDisconnectedBeforeResume = false
                        onConnectionRestored = null
                    }
                }

            override fun onFailure(throwable: Throwable) {
                this@MainActivity.isAppRemoteConnected = false
                Log.e(TAG, "App Remote connection failed: ${throwable.message}")
                // Retry in 5 seconds
                window.decorView.postDelayed({ connectToSpotifyAppRemote() }, 5000)
            }
        })
    }

    private fun authenticateWithWebApi() {
        if (isWebApiAuthenticated) {
            Log.d(TAG, "Already authenticated with Spotify Web API")
            return
        }
        
        Log.d(TAG, "🔐 Authenticating with Spotify Web API...")
        
        // Check if we already have a valid token
        if (AuthManager.isAuthenticated()) {
            isWebApiAuthenticated = true
            Log.d(TAG, "✅ Already authenticated with Spotify Web API")
            return
        }
        
        // If not, we'll authenticate on demand when needed (from fragments)
        Log.d(TAG, "Web API authentication will be performed on demand")
    }

    fun requestWebApiAuthentication(callback: (Boolean) -> Unit) {
        if (isWebApiAuthenticated) {
            Log.d(TAG, "Already authenticated, returning true")
            callback(true)
            return
        }
        
        Log.d(TAG, "Launching Web API authentication...")
        pendingAuthCallback = callback
        val authIntent = authManager.startAuthentication(CLIENT_ID, REDIRECT_URI)
        authLauncher.launch(authIntent)
    }
    
    fun getApiService(): SpotifyApiService? {
        if (cachedApiService == null) {
            val token = AuthManager.getAccessToken()
            if (token != null) {
                cachedApiService = SpotifyApiService(token)
                isWebApiAuthenticated = true
            }
        }
        return cachedApiService
    }

    fun showWorkoutSelectionFragment() {
        // Clear playlist and workout when starting new workout selection
        _currentPlaylist = emptyList()
        sharedPref.edit()
            .remove("current_playlist")
            .remove("current_workout_name")
            .apply()
        currentGeneratedPlaylist = null
        currentWorkout = null
        
        val fragment = WorkoutSelectionFragment()
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    fun showPlaylistGenerationFragment(workout: Workout) {
        currentWorkout = workout
        val fragment = PlaylistGenerationFragment()
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    fun showPlaybackFragment(playlist: List<WorkoutTrack>) {
        currentPlaylist = playlist
        val fragment = PlaybackFragment()
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    fun goBack() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
        } else {
            finish()
        }
    }
}
