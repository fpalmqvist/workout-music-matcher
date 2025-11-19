package se.fpq.remote.test

import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector

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
    var currentPlaylist: List<WorkoutTrack> = emptyList()
    var currentGeneratedPlaylist: GeneratedPlaylist? = null
    var shouldResumePlayback = false  // Flag to resume playback after reconnection
    var onConnectionRestored: (() -> Unit)? = null  // Callback to resync playback after reconnection
    var wasDisconnectedBeforeResume = false  // Track if we were actually disconnected when Fragment resumed
    
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
        
        // Connect to Spotify App Remote on startup
        connectToSpotifyAppRemote()
        
        // Authenticate with Web API on startup
        authenticateWithWebApi()
        
        // Show first fragment if this is first load
        if (savedInstanceState == null) {
            showWorkoutSelectionFragment()
        }
    }

    override fun onPause() {
        super.onPause()
        // Pause playback when leaving the app to avoid errors
        Log.d(TAG, "⏸️ Pausing playback (app going to background)")
        spotifyAppRemote?.playerApi?.pause()
    }

    override fun onResume() {
        super.onResume()
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
        // Gracefully disconnect when activity stops
        if (spotifyAppRemote != null) {
            SpotifyAppRemote.disconnect(spotifyAppRemote)
            isAppRemoteConnected = false
            Log.d(TAG, "👋 Disconnected from Spotify App Remote")
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
