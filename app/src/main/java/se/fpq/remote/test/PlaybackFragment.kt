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
        val tracks = activity.currentPlaylist
        
        if (tracks.isNotEmpty()) {
            displayTracks(tracks)
        }
        
        workoutTimerHandler = Handler(Looper.getMainLooper())
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
                orientation = LinearLayout.HORIZONTAL
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
            
            // Track name
            containerLayout.addView(TextView(requireContext()).apply {
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
            containerLayout.addView(TextView(requireContext()).apply {
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
            
            // BPM
            if (track.bpm != null) {
                containerLayout.addView(TextView(requireContext()).apply {
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
            
            tracksContainer.addView(containerLayout)
        }
    }

    private fun startPlayback() {
        val activity = requireActivity() as MainActivity
        val tracks = activity.currentPlaylist
        
        if (tracks.isEmpty() || activity.spotifyAppRemote == null) {
            Log.e(TAG, "Cannot start playback")
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
                startWorkoutTimer(tracks)
            }
            .setErrorCallback { error ->
                Log.e(TAG, "Playback error: ${error.message}")
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

            override fun run() {
                if (!isPlaying || currentTrackIndex >= tracks.size) {
                    if (currentTrackIndex >= tracks.size) {
                        Log.d(TAG, "✅ Workout completed!")
                    }
                    return
                }

                val currentTrack = tracks[currentTrackIndex]
                val trackDuration = (currentTrack.endTimeMs - currentTrack.startTimeMs).toLong()

                if (elapsedTime >= trackDuration) {
                    currentTrackIndex++
                    trackInfo.text = "Tracks: ${currentTrackIndex + 1}/${tracks.size}"
                    
                    if (currentTrackIndex < tracks.size) {
                        val nextTrack = tracks[currentTrackIndex]
                        Log.d(TAG, "▶️ Playing next: ${nextTrack.blockName}")

                        val activity = requireActivity() as MainActivity
                        activity.spotifyAppRemote!!.playerApi.play(nextTrack.trackUri)
                            .setErrorCallback { error ->
                                Log.e(TAG, "Playback error: ${error.message}")
                            }

                        elapsedTime = 0
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
        
        tracksContainer.removeAllViews()
        
        tracks.forEachIndexed { index, track ->
            val durationMs = track.endTimeMs - track.startTimeMs
            val durationSec = durationMs / 1000
            val durationMin = durationSec / 60
            val durationSecRem = durationSec % 60
            val durationStr = String.format("%d:%02d", durationMin, durationSecRem)
            
            val containerLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
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
            
            // Track name
            containerLayout.addView(TextView(requireContext()).apply {
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
            containerLayout.addView(TextView(requireContext()).apply {
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
            
            // BPM
            if (track.bpm != null) {
                containerLayout.addView(TextView(requireContext()).apply {
                    text = "${track.bpm} BPM"
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
            
            tracksContainer.addView(containerLayout)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopWorkoutTimer()
    }
}

