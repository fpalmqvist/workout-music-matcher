package se.fpq.remote.test

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import androidx.fragment.app.Fragment

class PlaybackFragment : Fragment() {
    companion object {
        private const val TAG = "Playback"
    }

    private var isPlaying = false
    private var currentTrackIndex = -1  // -1 means no track is currently playing
    private var workoutTimerHandler: Handler? = null
    private var workoutTimerRunnable: Runnable? = null
    private lateinit var tracksContainer: LinearLayout
    private lateinit var tracksScrollView: ScrollView
    private lateinit var playButton: Button
    private lateinit var pauseButton: Button
    private lateinit var trackInfo: TextView
    private var volumeTransitionManager: VolumeTransitionManager? = null
    private var spotifyCurrentTrackUri: String? = null  // What Spotify is ACTUALLY playing (only updated on successful play())
    
    // Track substitution manager for intelligent BPM-based matching
    private var substitutionManager: TrackSubstitutionManager? = null
    
    // Map of track index to cadence (for substitution logic)
    private val trackCadences = mutableMapOf<Int, Int?>()
    
    // Absolute workout start time (milliseconds since system boot)
    // This is used for accurate timing that doesn't drift across track transitions
    private var workoutStartTimeMs: Long = 0L
    
    // WakeLock to keep CPU awake during workouts (so transitions work even with screen off)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return LinearLayout(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
            setBackgroundColor(android.graphics.Color.WHITE)

            // Title
            addView(TextView(requireContext()).apply {
                text = "Workout Playback"
                textSize = 28f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 32
                }
            })

            // Control buttons
            val buttonContainer = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 24
                }

                playButton = Button(requireContext()).apply {
                    text = "▶ Start"
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                    ).apply {
                        marginEnd = 8
                    }
                    setBackgroundColor(android.graphics.Color.parseColor("#4CAF50"))
                    setTextColor(android.graphics.Color.WHITE)
                    setOnClickListener { startPlayback() }
                }
                addView(playButton)

                pauseButton = Button(requireContext()).apply {
                    text = "⏸ Pause"
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                    ).apply {
                        marginStart = 8
                    }
                    setBackgroundColor(android.graphics.Color.parseColor("#FF9800"))
                    setTextColor(android.graphics.Color.WHITE)
                    isEnabled = false
                    setOnClickListener { pausePlayback() }
                }
                addView(pauseButton)
            }
            addView(buttonContainer)

            // Track info
            trackInfo = TextView(requireContext()).apply {
                text = "Tracks: 0/0"
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 16
                }
            }
            addView(trackInfo)

            // ScrollView for tracks
            tracksScrollView = ScrollView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )

                tracksContainer = LinearLayout(requireContext()).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 0, 0, 0)
                }
                addView(tracksContainer)
            }
            addView(tracksScrollView)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val activity = requireActivity() as MainActivity
        val generatedPlaylist = activity.currentGeneratedPlaylist
        val tracks = activity.currentPlaylist
        
        // Restore state from saved instance
        val savedVolume: Int? = savedInstanceState?.let { bundle ->
            isPlaying = bundle.getBoolean("isPlaying", false)
            currentTrackIndex = bundle.getInt("currentTrackIndex", 0)
            workoutStartTimeMs = bundle.getLong("workoutStartTimeMs", 0L)
            spotifyCurrentTrackUri = bundle.getString("spotifyCurrentTrackUri")
            
            val volume = if (bundle.containsKey("savedVolume")) {
                bundle.getInt("savedVolume", -1).takeIf { it >= 0 }
            } else {
                null
            }
            
            Log.d(TAG, "🔄 Restored state: isPlaying=$isPlaying, currentTrackIndex=$currentTrackIndex, workoutStartTimeMs=$workoutStartTimeMs, savedVolume=$volume")
            volume
        }
        
        if (tracks.isNotEmpty()) {
            // Display tracks - will use updateTrackDisplay() which handles workout availability
            // If workout isn't restored yet, we'll re-display after a short delay
            updateTrackDisplay()
            
            // Initialize substitution manager with enriched source data
            if (generatedPlaylist != null && generatedPlaylist.sourceAllTracks.isNotEmpty()) {
                substitutionManager = TrackSubstitutionManager(
                    generatedPlaylist.sourceAllTracks,
                    generatedPlaylist.trackFeatures
                )
                // Tell substitution manager which tracks are currently in the playlist
                val playlistTrackIds = tracks.map { it.trackUri.substringAfterLast(":") }.toSet()
                substitutionManager?.setPlaylistTracks(playlistTrackIds)
                Log.d(TAG, "✅ Substitution manager initialized with ${generatedPlaylist.sourceAllTracks.size} tracks")
            }
            
            // If workout wasn't available, check again after a delay to re-display with correct colors
            if (activity.currentWorkout == null) {
                view.postDelayed({
                    val restoredWorkout = (requireActivity() as MainActivity).currentWorkout
                    if (restoredWorkout != null) {
                        Log.d(TAG, "🔄 Workout restored, updating track colors")
                        updateTrackDisplay()
                    } else {
                        Log.w(TAG, "⚠️ Workout still not available after delay")
                    }
                }, 200)
            }
        }
        
        workoutTimerHandler = Handler(Looper.getMainLooper())
        
        // Initialize volume transition manager for smooth fades
        volumeTransitionManager = VolumeTransitionManager(requireContext())
        
        // Restore volume to saved level if available
        savedVolume?.let { volume ->
            volumeTransitionManager?.restoreToVolume(volume)
            Log.d(TAG, "🔊 Restored volume to saved level: $volume")
        }
        
        // If playback was active, restore it
        if (isPlaying && workoutStartTimeMs > 0) {
            Log.d(TAG, "🔄 Restoring playback after rotation")
            // Update button states
            playButton.isEnabled = false
            pauseButton.isEnabled = true
            pauseButton.text = "⏸ Pause"
            // Update UI to show correct track
            updateTrackDisplay()
            // Resume playback
            resumePlaybackAfterRotation()
        } else {
            // Update button states for non-playing state
            playButton.isEnabled = true
            pauseButton.isEnabled = false
        }
    }
    
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        
        outState.putBoolean("isPlaying", isPlaying)
        outState.putInt("currentTrackIndex", currentTrackIndex)
        outState.putLong("workoutStartTimeMs", workoutStartTimeMs)
        spotifyCurrentTrackUri?.let {
            outState.putString("spotifyCurrentTrackUri", it)
        }
        
        // Save current volume level to preserve user's manual adjustments
        val audioManager = requireContext().getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        outState.putInt("savedVolume", currentVolume)
        
        Log.d(TAG, "💾 Saved state: isPlaying=$isPlaying, currentTrackIndex=$currentTrackIndex, workoutStartTimeMs=$workoutStartTimeMs, volume=$currentVolume")
    }
    
    /**
     * Resume playback after screen rotation
     */
    private fun resumePlaybackAfterRotation() {
        val activity = requireActivity() as MainActivity
        val tracks = activity.currentPlaylist
        
        if (tracks.isEmpty()) {
            Log.w(TAG, "⚠️ Cannot resume - no tracks available")
            return
        }
        
        // Calculate elapsed time since workout started (using same time source as timer)
        val currentTimeMs = System.currentTimeMillis()
        val elapsedTimeMs = currentTimeMs - workoutStartTimeMs
        
        Log.d(TAG, "▶️ Resuming playback: elapsed=${elapsedTimeMs}ms, workoutStartTime=${workoutStartTimeMs}, currentTime=${currentTimeMs}")
        
        // Find which track should be playing based on elapsed time
        // Tracks use absolute endTimeMs from workout start
        var targetTrackIndex = 0
        for ((index, track) in tracks.withIndex()) {
            if (elapsedTimeMs < track.endTimeMs) {
                targetTrackIndex = index
                break
            }
            targetTrackIndex = index + 1
        }
        
        // Make sure we don't go past the last track
        if (targetTrackIndex >= tracks.size) {
            targetTrackIndex = tracks.size - 1
            isPlaying = false
            Log.d(TAG, "✅ Workout already completed")
            return
        }
        
        currentTrackIndex = targetTrackIndex
        updateTrackDisplay()
        
        // Resume Spotify playback
        if (activity.spotifyAppRemote != null && activity.isAppRemoteConnected) {
            activity.spotifyAppRemote?.playerApi?.resume()
            Log.d(TAG, "✅ Resumed Spotify playback")
        } else {
            Log.w(TAG, "⚠️ Spotify not connected, will resume when connected")
            // Set callback to resume when connected
            activity.onConnectionRestored = {
                activity.spotifyAppRemote?.playerApi?.resume()
                Log.d(TAG, "✅ Resumed Spotify playback after reconnection")
            }
            activity.shouldResumePlayback = true
        }
        
        // Restart workout timer (it will use the saved workoutStartTimeMs)
        if (isPlaying) {
            startWorkoutTimer(tracks)
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Resume workout playback when fragment comes back into focus
        // and resync Spotify playback with the current elapsed time
        val act = activity as? MainActivity
        if (isPlaying) {
            Log.d(TAG, "🔄 Fragment resumed - updating UI with current elapsed time")
            
            // Update UI to show correct track based on elapsed time
            // This sets currentTrackIndex to the correct value
            updateTrackDisplay()
            
            if (act?.spotifyAppRemote != null && act.isAppRemoteConnected) {
                // Already connected - just resume
                Log.d(TAG, "✅ Spotify already connected - just resuming")
                act.wasDisconnectedBeforeResume = false
                act.resumeWorkoutPlayback()
            } else if (act != null && !act.isAppRemoteConnected) {
                // Not connected - set callback to resync after reconnection
                Log.d(TAG, "⏸️ Spotify disconnected - will reconnect and resync")
                act.wasDisconnectedBeforeResume = true  // Mark that we were disconnected
                act.shouldResumePlayback = true
                
                // Capture what track we SHOULD be on right now
                val tracks = act.currentPlaylist
                val correctTrackUri = if (currentTrackIndex >= 0 && currentTrackIndex < tracks.size) {
                    tracks[currentTrackIndex].trackUri
                } else {
                    null
                }
                
                act.onConnectionRestored = {
                    Log.d(TAG, "🔄 Connection restored - resyncing to correct track...")
                    // Resync if what we SHOULD be on is different from what Spotify ACTUALLY has
                    if (correctTrackUri != null && spotifyCurrentTrackUri != correctTrackUri) {
                        val tracks = act.currentPlaylist
                        if (currentTrackIndex >= 0 && currentTrackIndex < tracks.size) {
                            val currentTrack = tracks[currentTrackIndex]
                            Log.d(TAG, "🎵 Track changed while disconnected: ${currentTrack.blockName} (Spotify had: ${spotifyCurrentTrackUri?.substringAfterLast(":")})")
                            spotifyCurrentTrackUri = currentTrack.trackUri
                            act.spotifyAppRemote!!.playerApi.play(currentTrack.trackUri)
                                .setErrorCallback { error ->
                                    Log.e(TAG, "❌ Resync error: ${error.message}")
                                }
                        }
                    } else {
                        Log.d(TAG, "📍 Same track while disconnected - just resuming")
                    }
                    act.resumeWorkoutPlayback()
                }
                act.connectToSpotifyAppRemote()
            }
        }
    }
    
    /**
     * Convert dp to pixels for responsive sizing
     */
    private fun dpToPx(dp: Int): Int {
        return (dp * requireContext().resources.displayMetrics.density).toInt()
    }

    /**
     * Maps power percentage to Zwift zone color
     */
    private fun getPowerZoneColor(powerPercent: Int): Int {
        return when {
            powerPercent < 60 -> android.graphics.Color.parseColor("#808080")    // Grey - Recovery
            powerPercent < 76 -> android.graphics.Color.parseColor("#0066CC")    // Blue - Endurance
            powerPercent < 90 -> android.graphics.Color.parseColor("#00BB00")    // Green - Tempo
            powerPercent < 105 -> android.graphics.Color.parseColor("#FFFF00")   // Yellow - Threshold
            powerPercent < 119 -> android.graphics.Color.parseColor("#FF9900")   // Orange - VO2 Max
            else -> android.graphics.Color.parseColor("#FF0000")                 // Red - Anaerobic
        }
    }

    /**
     * Creates a gradient drawable for power ranges (Warmup, Cooldown, Ramp)
     */
    private fun createGradientDrawable(powerLowPercent: Int, powerHighPercent: Int): GradientDrawable {
        val colorLow = getPowerZoneColor(powerLowPercent)
        val colorHigh = getPowerZoneColor(powerHighPercent)
        
        return GradientDrawable().apply {
            colors = intArrayOf(colorLow, colorHigh)
            orientation = GradientDrawable.Orientation.LEFT_RIGHT
            cornerRadius = dpToPx(8).toFloat()  // Rounded corners for all tracks
        }
    }

    /**
     * Get the power level for a track based on its timing and the current workout
     */
    private fun getPowerForTrack(track: WorkoutTrack): Int {
        val activity = requireActivity() as MainActivity
        val workout = activity.currentWorkout ?: return 50
        
        // Find which block this track belongs to by matching its start time
        // Calculate cumulative time through blocks
        var cumulativeTimeMs = 0
        for (block in workout.blocks) {
            val blockStartMs = cumulativeTimeMs
            val blockEndMs = cumulativeTimeMs + block.duration * 1000
            
            // Check if this track's start time falls within this block
            if (track.startTimeMs >= blockStartMs && track.startTimeMs < blockEndMs) {
                return when (block) {
                    is WorkoutBlock.Warmup -> {
                        // For warmup/cooldown/ramp, use average of low and high
                        ((block.powerLow + block.powerHigh) / 2.0 * 100).toInt()
                    }
                    is WorkoutBlock.SteadyState -> {
                        (block.power * 100).toInt()
                    }
                    is WorkoutBlock.Cooldown -> {
                        ((block.powerLow + block.powerHigh) / 2.0 * 100).toInt()
                    }
                    is WorkoutBlock.Interval -> {
                        (block.power * 100).toInt()
                    }
                    is WorkoutBlock.Ramp -> {
                        ((block.powerLow + block.powerHigh) / 2.0 * 100).toInt()
                    }
                    is WorkoutBlock.Freeride -> {
                        50  // Neutral
                    }
                }
            }
            
            cumulativeTimeMs = blockEndMs
        }
        return 50  // Default if no matching block found
    }

    private fun getCadenceForTrack(track: WorkoutTrack): Int? {
        val activity = requireActivity() as MainActivity
        val workout = activity.currentWorkout ?: return null
        
        // Find which block this track belongs to by matching its start time
        // Calculate cumulative time through blocks
        var cumulativeTimeMs = 0
        for (block in workout.blocks) {
            val blockStartMs = cumulativeTimeMs
            val blockEndMs = cumulativeTimeMs + block.duration * 1000
            
            // Check if this track's start time falls within this block
            if (track.startTimeMs >= blockStartMs && track.startTimeMs < blockEndMs) {
                return block.cadence
            }
            
            cumulativeTimeMs = blockEndMs
        }
        return null  // Default if no matching block found
    }

    private fun displayTracks(tracks: List<WorkoutTrack>) {
        tracksContainer.removeAllViews()
        trackInfo.text = "Tracks: 1/${tracks.size}"
        
        tracks.forEachIndexed { index, track ->
            val durationMs = track.endTimeMs - track.startTimeMs
            val durationSec = durationMs / 1000
            val durationMin = durationSec / 60
            val durationSecRem = durationSec % 60
            val durationStr = String.format("%d:%02d", durationMin, durationSecRem)
            
            // Get power from the workout block
            val powerPercent = getPowerForTrack(track)
            val powerZoneColor = getPowerZoneColor(powerPercent)
            
            // Determine if this is a ramp block (needs gradient)
            val activity = requireActivity() as MainActivity
            val workout = activity.currentWorkout
            var isRampBlock = false
            var powerLow = 0
            var powerHigh = 0
            
            if (workout != null) {
                var cumulativeTimeMs = 0
                for (block in workout.blocks) {
                    val blockStartMs = cumulativeTimeMs
                    val blockEndMs = cumulativeTimeMs + block.duration * 1000
                    
                    if (track.startTimeMs >= blockStartMs && track.startTimeMs < blockEndMs) {
                        when (block) {
                            is WorkoutBlock.Warmup, is WorkoutBlock.Cooldown, is WorkoutBlock.Ramp -> {
                                isRampBlock = true
                                powerLow = when (block) {
                                    is WorkoutBlock.Warmup -> (block.powerLow * 100).toInt()
                                    is WorkoutBlock.Cooldown -> (block.powerLow * 100).toInt()
                                    is WorkoutBlock.Ramp -> (block.powerLow * 100).toInt()
                                    else -> 0
                                }
                                powerHigh = when (block) {
                                    is WorkoutBlock.Warmup -> (block.powerHigh * 100).toInt()
                                    is WorkoutBlock.Cooldown -> (block.powerHigh * 100).toInt()
                                    is WorkoutBlock.Ramp -> (block.powerHigh * 100).toInt()
                                    else -> 0
                                }
                            }
                            else -> {}
                        }
                        break
                    }
                    cumulativeTimeMs = blockEndMs
                }
            }
            
            // Determine text color based on background brightness
            val textColor = if (powerPercent < 76) android.graphics.Color.WHITE else android.graphics.Color.BLACK
            
            val containerLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dpToPx(12)
                    marginStart = dpToPx(12)
                    marginEnd = dpToPx(12)
                }
                
                // Use gradient for ramp blocks, solid color otherwise - both with rounded corners
                val cornerRadius = dpToPx(8).toFloat()
                if (isRampBlock) {
                    background = createGradientDrawable(powerLow, powerHigh)
                } else {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(powerZoneColor)
                        this.cornerRadius = cornerRadius
                    }
                }
                
                setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            
            // Track name (flexible, takes most space)
            containerLayout.addView(TextView(requireContext()).apply {
                text = "${index + 1}. ${track.blockName}"
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
                setTextColor(textColor)
            })
            
            // Duration and BPM stacked vertically (right-aligned with fixed width)
            val metaLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    dpToPx(70),  // Fixed width for alignment
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dpToPx(12)
                    marginEnd = dpToPx(12)
                }
            }
            
            // Duration (on top, right-aligned)
            metaLayout.addView(TextView(requireContext()).apply {
                text = durationStr
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                textAlignment = android.view.View.TEXT_ALIGNMENT_TEXT_END
                setTextColor(textColor)
            })
            
            // BPM (on middle, only if available, right-aligned)
            if (track.bpm != null && track.bpm > 0) {
                metaLayout.addView(TextView(requireContext()).apply {
                    text = "${track.bpm} BPM"
                    textSize = 11f
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    textAlignment = android.view.View.TEXT_ALIGNMENT_TEXT_END
                    setTextColor(textColor)
                })
            }
            
            // Target RPM (on bottom, only if available, right-aligned)
            val targetCadence = getCadenceForTrack(track)
            if (targetCadence != null && targetCadence > 0) {
                metaLayout.addView(TextView(requireContext()).apply {
                    text = "${targetCadence} RPM"
                    textSize = 11f
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    textAlignment = android.view.View.TEXT_ALIGNMENT_TEXT_END
                    setTextColor(textColor)
                })
            }
            
            containerLayout.addView(metaLayout)
            
            // Substitute button with light blue background
            val buttonSize = dpToPx(60)
            val buttonDrawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#87CEEB"))  // Light blue
                cornerRadius = dpToPx(6).toFloat()
            }
            
            containerLayout.addView(Button(requireContext()).apply {
                text = "↻"  // Modern refresh arrow symbol
                textSize = 24f
                layoutParams = LinearLayout.LayoutParams(
                    buttonSize,
                    buttonSize
                ).apply {
                    marginEnd = dpToPx(4)
                }
                background = buttonDrawable
                setTextColor(android.graphics.Color.BLACK)
                setPadding(0, 0, 0, 0)
                gravity = android.view.Gravity.CENTER
                isAllCaps = false
                setOnClickListener {
                    substituteTrack(index, track)
                }
            })
            
            tracksContainer.addView(containerLayout)
        }
    }

    /**
     * Helper function to play a track with multiple fallback strategies
     * Some tracks may fail with full URI but work with just the track ID
     */
    private fun playTrackWithFallback(trackUri: String, trackName: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val activity = requireActivity() as MainActivity
        val playerApi = activity.spotifyAppRemote?.playerApi ?: run {
            onError("Spotify not connected")
            return
        }
        
        // Log detailed URI information for debugging
        Log.d(TAG, "🔍 URI details for '$trackName':")
        Log.d(TAG, "   URI string: '$trackUri'")
        Log.d(TAG, "   URI length: ${trackUri.length}")
        Log.d(TAG, "   URI bytes: ${trackUri.toByteArray().joinToString(", ") { it.toString() }}")
        Log.d(TAG, "   URI chars: ${trackUri.map { it.code }.joinToString(", ")}")
        
        // Extract track ID (format: spotify:track:ID)
        val trackId = if (trackUri.startsWith("spotify:track:")) {
            trackUri.substringAfterLast(":")
        } else {
            trackUri
        }
        
        Log.d(TAG, "   Track ID: '$trackId'")
        
        // Try playing with full URI first
        Log.d(TAG, "🎵 Attempt 1: Playing with full URI")
        playerApi.play(trackUri)
            .setResultCallback {
                Log.d(TAG, "✅ Success with full URI")
                onSuccess()
            }
            .setErrorCallback { error ->
                Log.w(TAG, "⚠️ Failed with full URI: ${error.message}")
                // Try with just track ID
                Log.d(TAG, "🎵 Attempt 2: Playing with track ID only")
                playerApi.play(trackId)
                    .setResultCallback {
                        Log.d(TAG, "✅ Success with track ID")
                        onSuccess()
                    }
                    .setErrorCallback { idError ->
                        Log.w(TAG, "⚠️ Failed with track ID: ${idError.message}")
                        // Try reconstructing URI
                        val reconstructedUri = "spotify:track:$trackId"
                        if (reconstructedUri != trackUri) {
                            Log.d(TAG, "🎵 Attempt 3: Playing with reconstructed URI")
                            playerApi.play(reconstructedUri)
                                .setResultCallback {
                                    Log.d(TAG, "✅ Success with reconstructed URI")
                                    onSuccess()
                                }
                                .setErrorCallback { reconError ->
                                    Log.e(TAG, "❌ All attempts failed. Last error: ${reconError.message}")
                                    onError(reconError.message ?: "Unknown error")
                                }
                        } else {
                            Log.e(TAG, "❌ All attempts failed. Last error: ${idError.message}")
                            onError(idError.message ?: "Unknown error")
                        }
                    }
            }
    }
    
    private fun startPlayback() {
        val activity = requireActivity() as MainActivity
        val tracks = activity.currentPlaylist
        
        if (tracks.isEmpty()) {
            Log.e(TAG, "❌ Cannot start playback - no tracks")
            return
        }

        if (activity.spotifyAppRemote == null || !activity.isAppRemoteConnected) {
            Log.e(TAG, "❌ Cannot start playback - Spotify not connected")
            android.widget.Toast.makeText(
                requireContext(),
                "Spotify connection lost. Reconnecting...",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            activity.connectToSpotifyAppRemote()
            return
        }

        isPlaying = true
        playButton.isEnabled = false
        pauseButton.isEnabled = true
        
        // Flag that we're in a workout to prevent app pause from stopping playback
        activity.isWorkoutPlaying = true
        // Keep screen on during workout
        activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Acquire WakeLock to keep CPU awake even with screen off
        // This ensures transitions work even when screen is off
        try {
            if (wakeLock?.isHeld == false || wakeLock == null) {
                val powerManager = requireContext().getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "WorkoutMusicMatcher:WorkoutPlayback"
                ).apply {
                    acquire(Long.MAX_VALUE)  // Hold indefinitely until released
                    Log.d(TAG, "🔋 WakeLock acquired - CPU will stay awake during workout")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring WakeLock", e)
        }

        val firstTrack = tracks[0]
        Log.d(TAG, "🎵 Attempting to start playback: ${firstTrack.blockName} (URI: ${firstTrack.trackUri})")
        playTrackWithFallback(
            trackUri = firstTrack.trackUri,
            trackName = firstTrack.blockName,
            onSuccess = {
                Log.d(TAG, "✅ Play API call succeeded for: ${firstTrack.blockName}")
                spotifyCurrentTrackUri = firstTrack.trackUri  // Spotify is now playing this track
                // Set current track index and update display only after playback actually starts
                currentTrackIndex = 0
                updateTrackDisplay()
                
                // Verify playback actually started by checking player state after a delay
                // Use longer delay to allow Spotify time to load track metadata
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        activity.spotifyAppRemote?.playerApi?.playerState?.setResultCallback { playerState ->
                            val currentUri = playerState.track?.uri
                            val playerIsPlaying = playerState.isPaused.not()
                            val trackName = playerState.track?.name
                            Log.d(TAG, "🔍 Initial playback verification - URI: $currentUri, Track: $trackName, isPlaying: $playerIsPlaying")
                            
                            // If track URI is null but player is playing, wait a bit more and check again
                            if (currentUri == null && playerIsPlaying) {
                                Log.w(TAG, "⚠️ Track metadata not loaded yet, checking again in 1 second...")
                                Handler(Looper.getMainLooper()).postDelayed({
                                    activity.spotifyAppRemote?.playerApi?.playerState?.setResultCallback { retryState ->
                                        val retryUri = retryState.track?.uri
                                        val retryIsPlaying = retryState.isPaused.not()
                                        val retryTrackName = retryState.track?.name
                                        Log.d(TAG, "🔍 Retry verification - URI: $retryUri, Track: $retryTrackName, isPlaying: $retryIsPlaying")
                                        
                                        if (retryUri != firstTrack.trackUri) {
                                            Log.w(TAG, "⚠️ Track still mismatch after retry! Expected: ${firstTrack.trackUri}, Got: $retryUri")
                                            // Final retry of playback - pause first, then play
                                            Log.d(TAG, "🔄 Final retry initial playback for: ${firstTrack.blockName} (pausing first)")
                                            activity.spotifyAppRemote?.playerApi?.pause()
                                            Handler(Looper.getMainLooper()).postDelayed({
                                                activity.spotifyAppRemote?.playerApi?.play(firstTrack.trackUri)
                                                    ?.setResultCallback {
                                                        Log.d(TAG, "✅ Final retry successful for: ${firstTrack.blockName}")
                                                        spotifyCurrentTrackUri = firstTrack.trackUri
                // Only start timer if not already running (to preserve workout start time)
                if (workoutStartTimeMs == 0L) {
                    startWorkoutTimer(tracks)
                }
            }
                                                    ?.setErrorCallback { retryError ->
                                                        Log.e(TAG, "❌ Final retry failed: ${retryError.message}")
                                                        playButton.isEnabled = true
                                                        pauseButton.isEnabled = false
                                                        isPlaying = false
                                                        currentTrackIndex = -1
                                                        activity.isWorkoutPlaying = false
                                                        activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                                        updateTrackDisplay()
                                                    }
                                            }, 200) // Wait 200ms after pause
                                        } else if (!retryIsPlaying) {
                                            Log.w(TAG, "⚠️ Track loaded but not playing. Resuming...")
                                            activity.spotifyAppRemote?.playerApi?.resume()
                                            spotifyCurrentTrackUri = firstTrack.trackUri
                                            // Only start timer if not already running (to preserve workout start time)
                                            if (workoutStartTimeMs == 0L) {
                                                startWorkoutTimer(tracks)
                                            }
                                        } else {
                                            Log.d(TAG, "✅ Playback verified after retry: ${firstTrack.blockName} is playing")
                                            spotifyCurrentTrackUri = firstTrack.trackUri
                                            // Only start timer if not already running (to preserve workout start time)
                                            if (workoutStartTimeMs == 0L) {
                                                startWorkoutTimer(tracks)
                                            }
                                        }
                                    }
                                    ?.setErrorCallback { retryStateError ->
                                        Log.e(TAG, "❌ Failed to get retry player state: ${retryStateError.message}")
                                        // Still start timer even if verification fails
                                        if (workoutStartTimeMs == 0L) {
                                            startWorkoutTimer(tracks)
                                        }
                                    }
                                }, 1000) // Wait 1 more second
                            } else if (currentUri != firstTrack.trackUri) {
                                Log.w(TAG, "⚠️ Track mismatch! Expected: ${firstTrack.trackUri}, Got: $currentUri")
                                // Retry playback - pause first, then play
                                Log.d(TAG, "🔄 Retrying initial playback for: ${firstTrack.blockName} (pausing first)")
                                activity.spotifyAppRemote?.playerApi?.pause()
                                Handler(Looper.getMainLooper()).postDelayed({
                                    activity.spotifyAppRemote?.playerApi?.play(firstTrack.trackUri)
                                        ?.setResultCallback {
                                            Log.d(TAG, "✅ Retry successful for: ${firstTrack.blockName}")
                                            spotifyCurrentTrackUri = firstTrack.trackUri
                                            // Only start timer if not already running (to preserve workout start time)
                                            if (workoutStartTimeMs == 0L) {
                                                startWorkoutTimer(tracks)
                                            }
                                        }
                                        ?.setErrorCallback { retryError ->
                                            Log.e(TAG, "❌ Retry failed: ${retryError.message}")
                                            playButton.isEnabled = true
                                            pauseButton.isEnabled = false
                                            isPlaying = false
                                            currentTrackIndex = -1
                                            activity.isWorkoutPlaying = false
                                            activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                            updateTrackDisplay()
                                        }
                                }, 200) // Wait 200ms after pause
                            } else if (!playerIsPlaying) {
                                Log.w(TAG, "⚠️ Track loaded but not playing. Resuming...")
                                activity.spotifyAppRemote?.playerApi?.resume()
                                spotifyCurrentTrackUri = firstTrack.trackUri
                                // Only start timer if not already running (to preserve workout start time)
                                if (workoutStartTimeMs == 0L) {
                                    startWorkoutTimer(tracks)
                                }
                            } else {
                                Log.d(TAG, "✅ Playback verified: ${firstTrack.blockName} is playing")
                                spotifyCurrentTrackUri = firstTrack.trackUri
                                // Only start timer if not already running (to preserve workout start time)
                                if (workoutStartTimeMs == 0L) {
                                    startWorkoutTimer(tracks)
                                }
                            }
                        }
                        ?.setErrorCallback { stateError ->
                            Log.e(TAG, "❌ Failed to get player state: ${stateError.message}")
                            // Still start timer even if verification fails
                            if (workoutStartTimeMs == 0L) {
                                startWorkoutTimer(tracks)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error verifying playback state", e)
                        // Still start timer even if verification fails
                        if (workoutStartTimeMs == 0L) {
                            startWorkoutTimer(tracks)
                        }
                    }
                }, 1500) // Check after 1.5 seconds to allow track metadata to load
            },
            onError = { errorMessage ->
                Log.e(TAG, "❌ Playback error for ${firstTrack.blockName}: $errorMessage")
                playButton.isEnabled = true
                pauseButton.isEnabled = false
                isPlaying = false
                currentTrackIndex = -1  // Reset to no track selected
                activity.isWorkoutPlaying = false
                activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                updateTrackDisplay()  // Update to remove highlight
            }
        )
    }

    private fun pausePlayback() {
        val activity = requireActivity() as MainActivity
        
        if (isPlaying) {
            activity.spotifyAppRemote?.playerApi?.pause()
            pauseButton.text = "▶ Resume"
            isPlaying = false
        } else {
            activity.spotifyAppRemote?.playerApi?.resume()
            pauseButton.text = "⏸ Pause"
            isPlaying = true
        }
    }

    private fun stopPlayback() {
        val activity = requireActivity() as MainActivity
        activity.spotifyAppRemote?.playerApi?.pause()
        stopWorkoutTimer()
        isPlaying = false
        currentTrackIndex = -1  // Reset to no track selected
        activity.isWorkoutPlaying = false
        activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateTrackDisplay()  // Update to remove highlight
        
        // Release WakeLock so CPU can sleep normally
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "🔋 WakeLock released - CPU can sleep normally")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock", e)
        }
        
        Log.d(TAG, "⏹️ Playback stopped - screen can dim normally")
    }

    private fun startWorkoutTimer(tracks: List<WorkoutTrack>) {
        // CRITICAL FIX: Record absolute workout start time
        // This prevents timing drift across track transitions by using absolute time
        // instead of per-track elapsed time
        // Only set if not already set (e.g., from saved state)
        if (workoutStartTimeMs == 0L) {
            workoutStartTimeMs = System.currentTimeMillis()
            Log.d(TAG, "⏱️ Started new workout timer: $workoutStartTimeMs")
        } else {
            Log.d(TAG, "⏱️ Resuming workout timer: $workoutStartTimeMs")
        }
        
        workoutTimerRunnable = object : Runnable {
            private var isFading = false

            override fun run() {
                if (!isPlaying || currentTrackIndex >= tracks.size) {
                    if (currentTrackIndex >= tracks.size) {
                        Log.d(TAG, "✅ Workout completed!")
                    }
                    return
                }

                // CRITICAL FIX: Use absolute time from workout start, not per-track time
                val workoutElapsedTimeMs = System.currentTimeMillis() - workoutStartTimeMs
                val currentTrack = tracks[currentTrackIndex]

                // Check if we've reached the end time of the current track (absolute time from workout start)
                if (workoutElapsedTimeMs >= currentTrack.endTimeMs && !isFading) {
                    isFading = true
                    currentTrackIndex++
                    trackInfo.text = "Tracks: ${currentTrackIndex + 1}/${tracks.size}"
                    
                    if (currentTrackIndex < tracks.size) {
                        val nextTrack = tracks[currentTrackIndex]
                        Log.d(TAG, "▶️ Playing next: ${nextTrack.blockName}")

                        val activity = requireActivity() as MainActivity
                        
                        // Use fade transition when switching tracks
                        if (activity.spotifyAppRemote != null && activity.isAppRemoteConnected) {
                            volumeTransitionManager?.fadeOutThenIn {
                                Log.d(TAG, "🎵 Attempting to play: ${nextTrack.blockName} (URI: ${nextTrack.trackUri})")
                                // Pause current playback first to ensure clean transition
                                activity.spotifyAppRemote?.playerApi?.pause()
                                // Small delay to ensure fade-out and pause are complete before starting new track
                                Handler(Looper.getMainLooper()).postDelayed({
                                    playTrackWithFallback(
                                        trackUri = nextTrack.trackUri,
                                        trackName = nextTrack.blockName,
                                        onSuccess = {
                                            Log.d(TAG, "✅ Play API call succeeded for: ${nextTrack.blockName}")
                                        spotifyCurrentTrackUri = nextTrack.trackUri  // Spotify now playing this track
                                        
                                        // Verify playback actually started by checking player state after a delay
                                        // Use longer delay to allow Spotify time to load track metadata
                                        Handler(Looper.getMainLooper()).postDelayed({
                                            try {
                                                activity.spotifyAppRemote?.playerApi?.playerState?.setResultCallback { playerState ->
                                                    val currentUri = playerState.track?.uri
                                                    val playerIsPlaying = playerState.isPaused.not()
                                                    val trackName = playerState.track?.name
                                                    Log.d(TAG, "🔍 Playback verification - URI: $currentUri, Track: $trackName, isPlaying: $playerIsPlaying")
                                                    
                                                    // If track URI is null but player is playing, wait a bit more and check again
                                                    if (currentUri == null && playerIsPlaying) {
                                                        Log.w(TAG, "⚠️ Track metadata not loaded yet, checking again in 1 second...")
                                                        Handler(Looper.getMainLooper()).postDelayed({
                                                            activity.spotifyAppRemote?.playerApi?.playerState?.setResultCallback { retryState ->
                                                                val retryUri = retryState.track?.uri
                                                                val retryIsPlaying = retryState.isPaused.not()
                                                                val retryTrackName = retryState.track?.name
                                                                Log.d(TAG, "🔍 Retry verification - URI: $retryUri, Track: $retryTrackName, isPlaying: $retryIsPlaying")
                                                                
                                                                if (retryUri != nextTrack.trackUri) {
                                                                    Log.w(TAG, "⚠️ Track still mismatch after retry! Expected: ${nextTrack.trackUri}, Got: $retryUri")
                                                                    // Final retry of playback - pause first, then play
                                                                    Log.d(TAG, "🔄 Final retry playback for: ${nextTrack.blockName} (pausing first)")
                                                                    activity.spotifyAppRemote?.playerApi?.pause()
                                                                    Handler(Looper.getMainLooper()).postDelayed({
                                                                        activity.spotifyAppRemote?.playerApi?.play(nextTrack.trackUri)
                                                                            ?.setResultCallback {
                                                                                Log.d(TAG, "✅ Final retry successful for: ${nextTrack.blockName}")
                                                                                spotifyCurrentTrackUri = nextTrack.trackUri
                                                                                // Verify one more time after final retry
                                                                                Handler(Looper.getMainLooper()).postDelayed({
                                                                                    activity.spotifyAppRemote?.playerApi?.playerState?.setResultCallback { finalState ->
                                                                                        val finalUri = finalState.track?.uri
                                                                                        Log.d(TAG, "🔍 Final verification - URI: $finalUri")
                                                                                        if (finalUri == nextTrack.trackUri) {
                                                                                            Log.d(TAG, "✅ Track confirmed playing: ${nextTrack.blockName}")
                                                                                        } else {
                                                                                            Log.w(TAG, "⚠️ Track still not confirmed after final retry - will continue with workout timing")
                                                                                            // Don't skip - keep timing correct, track may play later or be silent
                                                                                        }
                                                                                    }
                                                                                }, 1000)
                                                                            }
                                                                            ?.setErrorCallback { retryError ->
                                                                                Log.e(TAG, "❌ Final retry failed: ${retryError.message}")
                                                                                Log.w(TAG, "⚠️ Track failed to play: ${nextTrack.blockName} - workout timing will continue")
                                                                                // Don't skip - keep workout timing correct, track may be silent
                                                                            }
                                                                    }, 200) // Wait 200ms after pause
                                                                } else if (!retryIsPlaying) {
                                                                    Log.w(TAG, "⚠️ Track loaded but not playing. Resuming...")
                                                                    activity.spotifyAppRemote?.playerApi?.resume()
                                                                    // Verify resume worked after a delay
                                                                    Handler(Looper.getMainLooper()).postDelayed({
                                                                        activity.spotifyAppRemote?.playerApi?.playerState?.setResultCallback { resumeState ->
                                                                            val resumeUri = resumeState.track?.uri
                                                                            val resumeIsPlaying = resumeState.isPaused.not()
                                                                            if (resumeUri == nextTrack.trackUri && resumeIsPlaying) {
                                                                                Log.d(TAG, "✅ Track playing after resume: ${nextTrack.blockName}")
                                                                                spotifyCurrentTrackUri = nextTrack.trackUri
                                                                            } else {
                                                                                Log.w(TAG, "⚠️ Track still not playing after resume: ${nextTrack.blockName} - workout timing will continue")
                                                                                // Don't skip - keep workout timing correct
                                                                            }
                                                                        }
                                                                    }, 500)
                                                                } else {
                                                                    Log.d(TAG, "✅ Playback verified after retry: ${nextTrack.blockName} is playing")
                                                                    spotifyCurrentTrackUri = nextTrack.trackUri
                                                                }
                                                            }
                                                            ?.setErrorCallback { retryStateError ->
                                                                Log.e(TAG, "❌ Failed to get retry player state: ${retryStateError.message}")
                                                            }
                                                        }, 1000) // Wait 1 more second
                                                    } else if (currentUri != nextTrack.trackUri) {
                                                        Log.w(TAG, "⚠️ Track mismatch! Expected: ${nextTrack.trackUri}, Got: $currentUri")
                                                        // Retry playback - pause first, then play
                                                        Log.d(TAG, "🔄 Retrying playback for: ${nextTrack.blockName} (pausing first)")
                                                        activity.spotifyAppRemote?.playerApi?.pause()
                                                        Handler(Looper.getMainLooper()).postDelayed({
                                                            activity.spotifyAppRemote?.playerApi?.play(nextTrack.trackUri)
                                                                ?.setResultCallback {
                                                                    Log.d(TAG, "✅ Retry successful for: ${nextTrack.blockName}")
                                                                    spotifyCurrentTrackUri = nextTrack.trackUri
                                                                }
                                                                ?.setErrorCallback { retryError ->
                                                                    Log.e(TAG, "❌ Retry failed: ${retryError.message}")
                                                                    Log.w(TAG, "⚠️ Track failed to play: ${nextTrack.blockName} - workout timing will continue")
                                                                    // Don't skip - keep workout timing correct
                                                                }
                                                        }, 200) // Wait 200ms after pause
                                                    } else if (!playerIsPlaying) {
                                                        Log.w(TAG, "⚠️ Track loaded but not playing. Resuming...")
                                                        activity.spotifyAppRemote?.playerApi?.resume()
                                                        // Verify resume worked after a delay
                                                        Handler(Looper.getMainLooper()).postDelayed({
                                                            activity.spotifyAppRemote?.playerApi?.playerState?.setResultCallback { resumeState ->
                                                                val resumeUri = resumeState.track?.uri
                                                                val resumeIsPlaying = resumeState.isPaused.not()
                                                                if (resumeUri == nextTrack.trackUri && resumeIsPlaying) {
                                                                    Log.d(TAG, "✅ Track playing after resume: ${nextTrack.blockName}")
                                                                    spotifyCurrentTrackUri = nextTrack.trackUri
                                                                } else {
                                                                    Log.w(TAG, "⚠️ Track still not playing after resume: ${nextTrack.blockName} - workout timing will continue")
                                                                    // Don't skip - keep workout timing correct
                                                                }
                                                            }
                                                        }, 500)
                                                    } else {
                                                        Log.d(TAG, "✅ Playback verified: ${nextTrack.blockName} is playing")
                                                        spotifyCurrentTrackUri = nextTrack.trackUri
                                                    }
                                                }
                                                ?.setErrorCallback { stateError ->
                                                    Log.e(TAG, "❌ Failed to get player state: ${stateError.message}")
                                                }
                                            } catch (e: Exception) {
                                                Log.e(TAG, "❌ Error verifying playback state", e)
                                            }
                                        }, 1500) // Check after 1.5 seconds to allow track metadata to load
                                        },
                                        onError = { errorMessage ->
                                            Log.e(TAG, "❌ Playback error for ${nextTrack.blockName}: $errorMessage")
                                            if (errorMessage.contains("not connected", ignoreCase = true)) {
                                            Log.w(TAG, "⚠️ Spotify disconnected - attempting reconnection...")
                                            activity.connectToSpotifyAppRemote()
                                        }
                                    }
                                    )
                                }, 100) // Small delay to ensure fade-out completes
                            }
                        } else {
                            Log.w(TAG, "⚠️ Spotify not connected, skipping track transition")
                            // Spotify is still playing the old track (no update to spotifyCurrentTrackUri)
                        }

                        isFading = false
                        updateTrackDisplay()
                    }
                }

                workoutTimerHandler?.postDelayed(this, 100)
            }
        }

        workoutTimerHandler?.post(workoutTimerRunnable!!)
    }

    private fun stopWorkoutTimer() {
        workoutTimerRunnable?.let { workoutTimerHandler?.removeCallbacks(it) }
        workoutTimerRunnable = null
    }
    

    private fun updateTrackDisplay() {
        val activity = requireActivity() as MainActivity
        val tracks = activity.currentPlaylist
        val generatedPlaylist = activity.currentGeneratedPlaylist
        
        tracksContainer.removeAllViews()
        
        tracks.forEachIndexed { index, track ->
            val durationMs = track.endTimeMs - track.startTimeMs
            val durationSec = durationMs / 1000
            val durationMin = durationSec / 60
            val durationSecRem = durationSec % 60
            val durationStr = String.format("%d:%02d", durationMin, durationSecRem)
            
            // Get BPM from features if available (for substituted tracks)
            val bpm = track.bpm ?: run {
                val trackId = track.trackUri.substringAfterLast(":")
                generatedPlaylist?.trackFeatures?.get(trackId)?.bpm
            }
            
            // Get power from the workout block for color coding
            val powerPercent = getPowerForTrack(track)
            val powerZoneColor = getPowerZoneColor(powerPercent)
            // Only highlight if actually playing and this is the current track
            val isCurrentTrack = isPlaying && index == currentTrackIndex && currentTrackIndex >= 0
            
            // Determine if this is a ramp block (needs gradient)
            var isRampBlock = false
            var powerLow = 0
            var powerHigh = 0
            
            if (!isCurrentTrack && activity.currentWorkout != null) {
                var cumulativeTimeMs = 0
                for (block in activity.currentWorkout!!.blocks) {
                    val blockStartMs = cumulativeTimeMs
                    val blockEndMs = cumulativeTimeMs + block.duration * 1000
                    
                    if (track.startTimeMs >= blockStartMs && track.startTimeMs < blockEndMs) {
                        when (block) {
                            is WorkoutBlock.Warmup, is WorkoutBlock.Cooldown, is WorkoutBlock.Ramp -> {
                                isRampBlock = true
                                powerLow = when (block) {
                                    is WorkoutBlock.Warmup -> (block.powerLow * 100).toInt()
                                    is WorkoutBlock.Cooldown -> (block.powerLow * 100).toInt()
                                    is WorkoutBlock.Ramp -> (block.powerLow * 100).toInt()
                                    else -> 0
                                }
                                powerHigh = when (block) {
                                    is WorkoutBlock.Warmup -> (block.powerHigh * 100).toInt()
                                    is WorkoutBlock.Cooldown -> (block.powerHigh * 100).toInt()
                                    is WorkoutBlock.Ramp -> (block.powerHigh * 100).toInt()
                                    else -> 0
                                }
                            }
                            else -> {}
                        }
                        break
                    }
                    cumulativeTimeMs = blockEndMs
                }
            }
            
            // Determine text color based on background brightness
            // Yellow (90-105) and Orange (105-119) need black text, others use white
            val textColor = when {
                // Yellow and Orange zones always use black text for readability
                powerPercent >= 90 && powerPercent < 119 -> android.graphics.Color.BLACK
                // Other zones use white text
                else -> android.graphics.Color.WHITE
            }
            
            val containerLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dpToPx(12)
                    marginStart = dpToPx(12)
                    marginEnd = dpToPx(12)
                }
                
                // Set background - use gradient for ramp blocks (only when not current track)
                val cornerRadius = dpToPx(8).toFloat()
                val backgroundDrawable = if (isRampBlock && !isCurrentTrack) {
                    createGradientDrawable(powerLow, powerHigh)
                } else {
                    android.graphics.drawable.GradientDrawable().apply {
                        setColor(powerZoneColor)
                        this.cornerRadius = cornerRadius
                    }
                }
                
                // Add border for current track (dark grey border for visibility)
                if (isCurrentTrack) {
                    val borderDrawable = android.graphics.drawable.GradientDrawable().apply {
                        setColor(powerZoneColor)
                        this.cornerRadius = cornerRadius
                        setStroke(dpToPx(4), android.graphics.Color.parseColor("#424242")) // Dark grey border
                    }
                    background = borderDrawable
                } else {
                    background = backgroundDrawable
                }
                
                setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            
            // Track name (flexible, takes most space)
            containerLayout.addView(TextView(requireContext()).apply {
                text = "${index + 1}. ${track.blockName}"
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
                setTextColor(textColor)
            })
            
            // Duration and BPM stacked vertically (right-aligned with fixed width)
            val metaLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    dpToPx(70),  // Fixed width for alignment
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dpToPx(12)
                    marginEnd = dpToPx(12)
                }
            }
            
            // Duration (on top, right-aligned)
            metaLayout.addView(TextView(requireContext()).apply {
                text = durationStr
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                textAlignment = android.view.View.TEXT_ALIGNMENT_TEXT_END
                setTextColor(textColor)
            })
            
            // BPM (on bottom, only if available, right-aligned)
            if (bpm != null && bpm > 0) {
                metaLayout.addView(TextView(requireContext()).apply {
                    text = "$bpm BPM"
                    textSize = 11f
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    textAlignment = android.view.View.TEXT_ALIGNMENT_TEXT_END
                    setTextColor(textColor)
                })
            }
            
            containerLayout.addView(metaLayout)
            
            // Substitute button with light blue background
            val buttonSize = dpToPx(60)
            val buttonDrawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#87CEEB"))  // Light blue
                cornerRadius = dpToPx(6).toFloat()
            }
            
            containerLayout.addView(Button(requireContext()).apply {
                text = "↻"  // Modern refresh arrow symbol
                textSize = 24f
                layoutParams = LinearLayout.LayoutParams(
                    buttonSize,
                    buttonSize
                ).apply {
                    marginEnd = dpToPx(4)
                }
                background = buttonDrawable
                setTextColor(android.graphics.Color.BLACK)
                setPadding(0, 0, 0, 0)
                gravity = android.view.Gravity.CENTER
                isAllCaps = false
                setOnClickListener {
                    substituteTrack(index, track)
                }
            })
            
            tracksContainer.addView(containerLayout)
        }
        
        // Scroll to current track if available and actually playing
        if (isPlaying && currentTrackIndex >= 0 && currentTrackIndex < tracks.size) {
            scrollToCurrentTrack()
        }
    }
    
    /**
     * Scroll the list to make the current track visible
     */
    private fun scrollToCurrentTrack() {
        if (currentTrackIndex < 0 || currentTrackIndex >= tracksContainer.childCount) {
            return
        }
        
        val currentTrackView = tracksContainer.getChildAt(currentTrackIndex)
        if (currentTrackView != null) {
            // Post to ensure layout is complete
            tracksScrollView.post {
                val scrollY = currentTrackView.top - tracksScrollView.height / 2 + currentTrackView.height / 2
                tracksScrollView.smoothScrollTo(0, scrollY.coerceAtLeast(0))
            }
        }
    }

    /**
     * Substitute a track with the next best BPM match
     */
    private fun substituteTrack(trackIndex: Int, currentTrack: WorkoutTrack) {
        val substitutionMgr = substitutionManager
        if (substitutionMgr == null) {
            Log.w(TAG, "⚠️ Substitution manager not available")
            return
        }
        
        val activity = requireActivity() as MainActivity
        val tracks = activity.currentPlaylist
        
        if (trackIndex < 0 || trackIndex >= tracks.size) {
            Log.w(TAG, "⚠️ Invalid track index: $trackIndex")
            return
        }
        
        // Find the cadence for this block (we need to get it from the original workout)
        val cadence = activity.currentWorkout?.blocks?.getOrNull(trackIndex)?.cadence
        Log.d(TAG, "🔄 Substituting track $trackIndex (cadence: $cadence RPM)")
        
        // Get next substitution
        val spotifyTrack = SpotifyTrack(
            id = currentTrack.trackUri.substringAfterLast(":"),
            name = currentTrack.blockName,
            artist = SpotifyArtist("", currentTrack.blockName, ""),
            album = SpotifyAlbum("", "", ""),
            durationMs = (currentTrack.endTimeMs - currentTrack.startTimeMs).toInt(),
            uri = currentTrack.trackUri
        )
        
        val nextTrack = substitutionMgr.getNextSubstitution(spotifyTrack, cadence)
        
        // Get BPM for the new track from features
        val nextTrackId = nextTrack.id
        val nextTrackBpm = activity.currentGeneratedPlaylist?.trackFeatures?.get(nextTrackId)?.bpm
        
        // Update current playlist
        val updatedTrack = WorkoutTrack(
            nextTrack.uri,
            currentTrack.startTimeMs,
            currentTrack.endTimeMs,
            nextTrack.name,
            cadence,
            nextTrackBpm  // Include BPM from features
        )
        
        // Update the tracks list
        val newTracks = tracks.toMutableList()
        newTracks[trackIndex] = updatedTrack
        activity.currentPlaylist = newTracks
        
        // Update substitution manager with new playlist composition
        val playlistTrackIds = newTracks.map { it.trackUri.substringAfterLast(":") }.toSet()
        substitutionManager?.setPlaylistTracks(playlistTrackIds)
        
        // Refresh display
        updateTrackDisplay()
        
        Log.d(TAG, "✅ Substituted: ${currentTrack.blockName} → ${nextTrack.name}")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopWorkoutTimer()
        volumeTransitionManager?.release()
        
        // Ensure WakeLock is released
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "🔋 WakeLock released in onDestroy")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock in onDestroy", e)
        }
    }
}

