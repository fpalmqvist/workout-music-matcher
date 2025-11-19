package se.fpq.remote.test

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
    
    private fun displayTracks(tracks: List<WorkoutTrack>) {
        tracksContainer.removeAllViews()
        trackInfo.text = "Tracks: 1/${tracks.size}"
        
        tracks.forEachIndexed { index, track ->
            val durationMs = track.endTimeMs - track.startTimeMs
            val durationSec = durationMs / 1000
            val durationMin = durationSec / 60
            val durationSecRem = durationSec % 60
            val durationStr = String.format("%d:%02d", durationMin, durationSecRem)
            
            val containerLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 8
                    marginStart = 12
                    marginEnd = 12
                }
                if (index == 0) {
                    setBackgroundColor(android.graphics.Color.parseColor("#6200EE"))
                } else {
                    setBackgroundColor(android.graphics.Color.parseColor("#E8E8E8"))
                }
                setPadding(12, 12, 12, 12)
            }
            
            // Track info row (name, duration, BPM)
            val infoLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            
            // Track name
            infoLayout.addView(TextView(requireContext()).apply {
                text = "${index + 1}. ${track.blockName}"
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
                if (index == 0) {
                    setTextColor(android.graphics.Color.WHITE)
                } else {
                    setTextColor(android.graphics.Color.BLACK)
                }
            })
            
            // Duration
            infoLayout.addView(TextView(requireContext()).apply {
                text = durationStr
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = 16
                }
                if (index == 0) {
                    setTextColor(android.graphics.Color.WHITE)
                } else {
                    setTextColor(android.graphics.Color.BLACK)
                }
            })
            
            // BPM (only show if available, not -1)
            if (track.bpm != null && track.bpm > 0) {
                infoLayout.addView(TextView(requireContext()).apply {
                    text = "${track.bpm} BPM"
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginStart = 12
                    }
                    if (index == 0) {
                        setTextColor(android.graphics.Color.WHITE)
                    } else {
                        setTextColor(android.graphics.Color.BLACK)
                    }
                })
            }
            
            containerLayout.addView(infoLayout)
            
            // Substitute button row (if there are alternatives available)
            // Note: We'll need to pass alternatives from PlaylistGenerationFragment
            // For now, just add a placeholder substitute button
            val buttonLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 8
                }
            }
            
            buttonLayout.addView(Button(requireContext()).apply {
                text = "🔄 Next Match"
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
                setBackgroundColor(if (index == 0) android.graphics.Color.parseColor("#4CAF50") else android.graphics.Color.parseColor("#2196F3"))
                setTextColor(android.graphics.Color.WHITE)
                setOnClickListener {
                    substituteTrack(index, track)
                }
            })
            
            containerLayout.addView(buttonLayout)
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

        val firstTrack = tracks[0]
        activity.spotifyAppRemote!!.playerApi.play(firstTrack.trackUri)
            .setResultCallback {
                Log.d(TAG, "▶️ Started playback: ${firstTrack.blockName}")
                spotifyCurrentTrackUri = firstTrack.trackUri  // Spotify is now playing this track
                startWorkoutTimer(tracks)
            }
            .setErrorCallback { error ->
                Log.e(TAG, "❌ Playback error: ${error.message}")
                playButton.isEnabled = true
                pauseButton.isEnabled = false
                isPlaying = false
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
    }

    private fun startWorkoutTimer(tracks: List<WorkoutTrack>) {
        workoutTimerRunnable = object : Runnable {
            private var elapsedTime = 0L
            private var isFading = false

            override fun run() {
                if (!isPlaying || currentTrackIndex >= tracks.size) {
                    if (currentTrackIndex >= tracks.size) {
                        Log.d(TAG, "✅ Workout completed!")
                    }
                    return
                }

                val currentTrack = tracks[currentTrackIndex]
                val trackDuration = (currentTrack.endTimeMs - currentTrack.startTimeMs).toLong()

                if (elapsedTime >= trackDuration && !isFading) {
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

                        elapsedTime = 0
                        isFading = false
                        updateTrackDisplay()
                    }
                }

                elapsedTime += 100
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
            
            val containerLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 8
                    marginStart = 12
                    marginEnd = 12
                }
                if (index == currentTrackIndex) {
                    setBackgroundColor(android.graphics.Color.parseColor("#6200EE"))
                } else {
                    setBackgroundColor(android.graphics.Color.parseColor("#E8E8E8"))
                }
                setPadding(12, 12, 12, 12)
            }
            
            // Track info row (name, duration, BPM)
            val infoLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            
            // Track name
            infoLayout.addView(TextView(requireContext()).apply {
                text = "${index + 1}. ${track.blockName}"
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
                if (index == currentTrackIndex) {
                    setTextColor(android.graphics.Color.WHITE)
                } else {
                    setTextColor(android.graphics.Color.BLACK)
                }
            })
            
            // Duration
            infoLayout.addView(TextView(requireContext()).apply {
                text = durationStr
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = 16
                }
                if (index == currentTrackIndex) {
                    setTextColor(android.graphics.Color.WHITE)
                } else {
                    setTextColor(android.graphics.Color.BLACK)
                }
            })
            
            // BPM (only show if available, not -1)
            if (bpm != null && bpm > 0) {
                infoLayout.addView(TextView(requireContext()).apply {
                    text = "$bpm BPM"
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginStart = 12
                    }
                    if (index == currentTrackIndex) {
                        setTextColor(android.graphics.Color.WHITE)
                    } else {
                        setTextColor(android.graphics.Color.BLACK)
                    }
                })
            }
            
            containerLayout.addView(infoLayout)
            
            // Substitute button row
            val buttonLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 8
                }
            }
            
            buttonLayout.addView(Button(requireContext()).apply {
                text = "🔄 Next Match"
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
                setBackgroundColor(if (index == currentTrackIndex) android.graphics.Color.parseColor("#4CAF50") else android.graphics.Color.parseColor("#2196F3"))
                setTextColor(android.graphics.Color.WHITE)
                setOnClickListener {
                    substituteTrack(index, track)
                }
            })
            
            containerLayout.addView(buttonLayout)
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
    }
}

