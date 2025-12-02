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
import androidx.fragment.app.Fragment

class PlaybackFragment : Fragment() {
    companion object {
        private const val TAG = "Playback"
    }

    private var isPlaying = false
    private var currentTrackIndex = 0
    private var workoutTimerHandler: Handler? = null
    private var workoutTimerRunnable: Runnable? = null
    private lateinit var tracksContainer: LinearLayout
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
            val scrollView = ScrollView(requireContext()).apply {
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
            addView(scrollView)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val activity = requireActivity() as MainActivity
        val generatedPlaylist = activity.currentGeneratedPlaylist
        val tracks = activity.currentPlaylist
        
        if (tracks.isNotEmpty()) {
            displayTracks(tracks)
            
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
        }
        
        workoutTimerHandler = Handler(Looper.getMainLooper())
        
        // Initialize volume transition manager for smooth fades
        volumeTransitionManager = VolumeTransitionManager(requireContext())
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
            cornerRadius = 0f
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
                
                // Use gradient for ramp blocks, solid color otherwise
                if (isRampBlock) {
                    background = createGradientDrawable(powerLow, powerHigh)
                } else {
                    setBackgroundColor(powerZoneColor)
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
        currentTrackIndex = 0
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
        activity.spotifyAppRemote!!.playerApi.play(firstTrack.trackUri)
            .setResultCallback {
                Log.d(TAG, "▶️ Started playback: ${firstTrack.blockName}")
                spotifyCurrentTrackUri = firstTrack.trackUri  // Spotify is now playing this track
                // Only start timer if not already running (to preserve workout start time)
                if (workoutStartTimeMs == 0L) {
                    startWorkoutTimer(tracks)
                }
            }
            .setErrorCallback { error ->
                Log.e(TAG, "❌ Playback error: ${error.message}")
                playButton.isEnabled = true
                pauseButton.isEnabled = false
                isPlaying = false
                activity.isWorkoutPlaying = false
                activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
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
        activity.isWorkoutPlaying = false
        activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
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
        workoutStartTimeMs = System.currentTimeMillis()
        
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
                                activity.spotifyAppRemote!!.playerApi.play(nextTrack.trackUri)
                                    .setResultCallback {
                                        spotifyCurrentTrackUri = nextTrack.trackUri  // Spotify now playing this track
                                    }
                                    .setErrorCallback { error ->
                                        Log.e(TAG, "❌ Playback error: ${error.message}")
                                        if (error.message?.contains("not connected", ignoreCase = true) == true) {
                                            Log.w(TAG, "⚠️ Spotify disconnected - attempting reconnection...")
                                            activity.connectToSpotifyAppRemote()
                                        }
                                    }
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
            val powerZoneColor = if (index == currentTrackIndex) {
                android.graphics.Color.parseColor("#6200EE")  // Current track highlight
            } else {
                getPowerZoneColor(powerPercent)
            }
            
            // Determine if this is a ramp block (needs gradient)
            var isRampBlock = false
            var powerLow = 0
            var powerHigh = 0
            
            if (index != currentTrackIndex && activity.currentWorkout != null) {
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
            val textColor = if (index == currentTrackIndex || powerPercent < 76) {
                android.graphics.Color.WHITE
            } else {
                android.graphics.Color.BLACK
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
                
                // Use gradient for ramp blocks (only when not current track), solid color otherwise
                if (isRampBlock && index != currentTrackIndex) {
                    background = createGradientDrawable(powerLow, powerHigh)
                } else {
                    setBackgroundColor(powerZoneColor)
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

