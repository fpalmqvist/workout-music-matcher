package se.fpq.remote.test

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PlaylistGenerationFragment : Fragment() {
    companion object {
        private const val TAG = "PlaylistGeneration"
    }

    private lateinit var blocksContainer: LinearLayout
    private lateinit var generateButton: Button

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
                text = "Generate Playlist"
                textSize = 28f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 32
                }
            })

            // Select Playlist button
            val selectPlaylistButton = Button(requireContext()).apply {
                text = "📋 Select Source Playlist"
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 12
                }
                setBackgroundColor(android.graphics.Color.parseColor("#2196F3"))
                setTextColor(android.graphics.Color.WHITE)
                setOnClickListener { showPlaylistSelector() }
            }
            addView(selectPlaylistButton)

            // Generate button
            generateButton = Button(requireContext()).apply {
                text = "🎵 Generate New Playlist"
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 24
                }
                setBackgroundColor(android.graphics.Color.parseColor("#4CAF50"))
                setTextColor(android.graphics.Color.WHITE)
                setOnClickListener { generatePlaylist() }
            }
            addView(generateButton)

            // Workout blocks section
            addView(TextView(requireContext()).apply {
                text = "Workout Blocks"
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 12
                }
            })

            // ScrollView for blocks
            val scrollView = ScrollView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                ).apply {
                    bottomMargin = 16
                }

                blocksContainer = LinearLayout(requireContext()).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 0, 0, 0)
                }
                addView(blocksContainer)
            }
            addView(scrollView)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val activity = requireActivity() as MainActivity
        val workout = activity.currentWorkout
        
        if (workout != null) {
            displayWorkoutBlocks(workout)
        }
    }

    private fun displayWorkoutBlocks(workout: Workout) {
        blocksContainer.removeAllViews()
        
        workout.blocks.forEach { block ->
            val blockDisplay = when (block) {
                is WorkoutBlock.Warmup -> "${block.duration}s Warmup @ ${block.cadence} rpm"
                is WorkoutBlock.SteadyState -> "${block.duration}s Steady @ ${block.cadence} rpm"
                is WorkoutBlock.Cooldown -> "${block.duration}s Cooldown @ ${block.cadence} rpm"
                else -> "Unknown block"
            }
            
            val blockView = TextView(requireContext()).apply {
                text = blockDisplay
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 8
                    marginStart = 12
                    marginEnd = 12
                }
                setBackgroundColor(android.graphics.Color.parseColor("#E8E8E8"))
                setPadding(12, 12, 12, 12)
            }
            blocksContainer.addView(blockView)
        }
    }

    private fun generatePlaylist() {
        generateButton.isEnabled = false
        generateButton.text = "⏳ Generating..."
        
        val activity = requireActivity() as MainActivity
        val workout = activity.currentWorkout
        
        if (workout == null) {
            generateButton.isEnabled = true
            generateButton.text = "🎵 Generate New Playlist"
            return
        }
        
        // First, ensure we're authenticated with Web API
        if (!activity.isWebApiAuthenticated) {
            Log.d(TAG, "Need to authenticate with Web API first...")
            activity.requestWebApiAuthentication { isAuthenticated ->
                if (isAuthenticated) {
                    Log.d(TAG, "✅ Web API authenticated, proceeding with playlist generation")
                    generatePlaylistWithApi(activity, workout)
                } else {
                    Log.w(TAG, "⚠️ Web API authentication failed, using demo playlist instead")
                    useDemoPlaylist(activity, workout)
                    generateButton.isEnabled = true
                    generateButton.text = "🎵 Generate New Playlist (Demo Mode)"
                }
            }
        } else {
            Log.d(TAG, "Already authenticated, proceeding with playlist generation")
            generatePlaylistWithApi(activity, workout)
        }
    }
    
    private fun generatePlaylistWithApi(activity: MainActivity, workout: Workout) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val apiService = activity.getApiService()
                
                if (apiService == null) {
                    Log.e(TAG, "API Service not available")
                    generateButton.isEnabled = true
                    generateButton.text = "🎵 Generate New Playlist (API Error)"
                    return@launch
                }
                
                // Check if user selected a playlist, otherwise fetch from saved tracks
                val selectedPlaylistId = activity.selectedPlaylistId
                
                val tracksTofetch = if (selectedPlaylistId != null) {
                    Log.d(TAG, "🔍 Fetching tracks from selected playlist...")
                    apiService.getPlaylistTracks(selectedPlaylistId).getOrNull() ?: emptyList()
                } else {
                    Log.d(TAG, "🔍 Fetching user's saved tracks...")
                    apiService.getUserSavedTracks().getOrNull() ?: emptyList()
                }
                
                val savedTracks = tracksTofetch
                Log.d(TAG, "✅ Found ${savedTracks.size} tracks")
                
                if (savedTracks.isEmpty()) {
                    Log.w(TAG, "⚠️ No saved tracks found, using demo tracks instead")
                    useDemoPlaylist(activity, workout)
                    return@launch
                }
                
                Log.d(TAG, "🔍 Fetching audio features for ${savedTracks.size} tracks...")
                // Extract track IDs from URIs (format: spotify:track:ID)
                val trackIds = savedTracks.map { track ->
                    track.uri.substringAfterLast(":")
                }
                Log.d(TAG, "  Sample IDs: ${trackIds.take(3).joinToString(", ")}")
                
                // Try to fetch audio features with track info, but don't fail if it doesn't work
                val audioFeatures = try {
                    val result = apiService.getAudioFeatures(trackIds, savedTracks)
                    (result.getOrNull() ?: emptyMap<String, SpotifyAudioFeatures>()).also {
                        if (it.isEmpty()) {
                            Log.w(TAG, "⚠️ Audio features API returned no data, using defaults")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Audio features fetch failed: ${e.message}, using defaults")
                    emptyMap()
                }.toMutableMap()  // Convert to mutable so we can update BPM values
                Log.d(TAG, "✅ Got audio features for ${audioFeatures.size} tracks (using defaults for missing)")
                
                // Enrich audio features with BPM data from GetSongKEY BEFORE generating playlist
                Log.d(TAG, "🔍 Enriching audio features with BPM data from GetSongKEY...")
                val songKeyService = GetSongKeyService(requireContext())
                var enrichedCount = 0
                var skippedCount = 0
                
                // Limit to first 50 tracks to avoid rate limiting (3000 requests/hour = ~50 per minute)
                val tracksToEnrich = savedTracks.take(50)
                
                for (track in tracksToEnrich) {
                    val artist = track.artist?.name ?: "Unknown"
                    val trackName = track.name ?: "Unknown"
                    
                    try {
                        // Use runBlocking since getBPMForTrack is a suspend function
                        val bpm = kotlinx.coroutines.runBlocking {
                            songKeyService.getBPMForTrack(artist, trackName)
                        }
                        if (bpm != null && bpm > 0) {
                            val existingFeatures = audioFeatures[track.id]
                            if (existingFeatures != null) {
                                audioFeatures[track.id] = existingFeatures.copy(bpm = bpm)
                                enrichedCount++
                                Log.d(TAG, "  ✅ Enriched: $trackName - $bpm BPM")
                            }
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "  ⚠️ Could not fetch BPM for $trackName: ${e.message}")
                        skippedCount++
                    }
                }
                Log.d(TAG, "✅ BPM enrichment complete: $enrichedCount tracks enriched, $skippedCount skipped (API rate limit protection)")
                
                Log.d(TAG, "🎵 Generating playlist using PlaylistGenerationEngine...")
                val engine = PlaylistGenerationEngine()
                val config = BpmMatchingConfig()
                val playlistResult = engine.generatePlaylist(workout, savedTracks, audioFeatures, config)
                val generatedPlaylist = playlistResult.getOrNull()
                
                if (generatedPlaylist == null) {
                    Log.w(TAG, "⚠️ Playlist generation failed, using demo tracks instead")
                    useDemoPlaylist(activity, workout)
                    return@launch
                }
                
                Log.d(TAG, "✅ Generated playlist with ${generatedPlaylist.trackSelections.size} tracks")
                
                // Convert to WorkoutTrack using enriched BPM from audioFeatures
                val workoutTracks = generatedPlaylist.trackSelections.map { selection: PlaylistTrackSelection ->
                    val bpm = audioFeatures[selection.track.id]?.bpm?.toInt() ?: 100
                    val trackName = selection.track.name ?: "Unknown Track"
                    
                    Log.d(TAG, "  Track: $trackName - BPM: $bpm")
                    
                    WorkoutTrack(
                        selection.track.uri,
                        (selection.startTime * 1000).toInt(),
                        (selection.endTime * 1000).toInt(),
                        trackName,
                        null,
                        bpm
                    )
                }
                
                workoutTracks.forEachIndexed { idx, track ->
                    Log.d(TAG, "  Track $idx: ${track.blockName} (${track.bpm} BPM) - ${track.trackUri}")
                }
                
                activity.currentPlaylist = workoutTracks
                activity.showPlaybackFragment(workoutTracks)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error generating playlist: ${e.message}", e)
                e.printStackTrace()
                generateButton.isEnabled = true
                generateButton.text = "🎵 Generate New Playlist (Error)"
            }
        }
    }
    
    private fun useDemoPlaylist(activity: MainActivity, workout: Workout) {
        Log.d(TAG, "Using demo playlist as fallback...")
        val workoutManager = WorkoutPlaybackManager()
        val demoTracks = workoutManager.initializeDemoWorkout()
        
        val playlist = mutableListOf<WorkoutTrack>()
        var timeOffset = 0L
        
        for ((index, block) in workout.blocks.withIndex()) {
            val blockDuration = when (block) {
                is WorkoutBlock.Warmup -> block.duration
                is WorkoutBlock.SteadyState -> block.duration
                is WorkoutBlock.Cooldown -> block.duration
                else -> 0
            }
            
            val track = demoTracks.getOrNull(index % demoTracks.size)
                ?: demoTracks.firstOrNull()
            
            if (track != null) {
                val startMs = timeOffset.toInt()
                val endMs = (timeOffset + (blockDuration * 1000)).toInt()
                playlist.add(WorkoutTrack(track.trackUri, startMs, endMs, track.blockName, null))
                timeOffset += (blockDuration * 1000)
            }
        }
        
        Log.d(TAG, "Generated fallback playlist with ${playlist.size} tracks")
        activity.currentPlaylist = playlist
        activity.showPlaybackFragment(playlist)
    }
    
    private fun showPlaylistSelector() {
        val activity = requireActivity() as MainActivity
        
        if (!activity.isWebApiAuthenticated) {
            Log.d(TAG, "Need to authenticate with Web API first...")
            activity.requestWebApiAuthentication { isAuthenticated ->
                if (isAuthenticated) {
                    Log.d(TAG, "✅ Authenticated, fetching playlists...")
                    fetchAndShowPlaylists(activity)
                }
            }
        } else {
            fetchAndShowPlaylists(activity)
        }
    }
    
    private fun fetchAndShowPlaylists(activity: MainActivity) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val apiService = activity.getApiService()
                if (apiService == null) {
                    Log.e(TAG, "API Service not available")
                    return@launch
                }
                
                Log.d(TAG, "🔍 Fetching user's playlists...")
                val playlistsResult = apiService.getUserPlaylists()
                val playlists = playlistsResult.getOrNull() ?: emptyList()
                Log.d(TAG, "✅ Found ${playlists.size} playlists")
                
                if (playlists.isEmpty()) {
                    Log.w(TAG, "⚠️ No playlists found")
                    return@launch
                }
                
                // Show playlist chooser dialog
                showPlaylistDialog(playlists, activity)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error fetching playlists: ${e.message}", e)
            }
        }
    }
    
    private fun showPlaylistDialog(playlists: List<SpotifyPlaylist>, activity: MainActivity) {
        val playlistNames = playlists.map { "${it.name} (${it.trackCount} tracks)" }.toTypedArray()
        val selectedIndex = playlists.indexOfFirst { it.id == activity.selectedPlaylistId }
        
        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setTitle("Select Playlist")
            .setSingleChoiceItems(playlistNames, selectedIndex) { dialog, which ->
                activity.selectedPlaylistId = playlists[which].id
                Log.d(TAG, "✅ Selected playlist: ${playlists[which].name}")
                dialog.dismiss()
            }
            .setNeutralButton("🔍 Search") { dialog, _ ->
                showPlaylistSearchDialog(activity)
                dialog.dismiss()
            }
            .setPositiveButton("📋 By ID") { dialog, _ ->
                showPlaylistIdDialog(activity)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .create()
        dialog.show()
    }
    
    private fun showPlaylistIdDialog(activity: MainActivity) {
        val editText = android.widget.EditText(requireContext()).apply {
            hint = "Paste playlist ID (e.g., 79ysEJO1gQzwOMCrFcCG8m)"
            setPadding(32, 32, 32, 32)
        }
        
        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setTitle("Enter Playlist ID")
            .setMessage("Get the ID from the Spotify playlist URL:\nhttps://open.spotify.com/playlist/[ID]")
            .setView(editText)
            .setPositiveButton("Load") { dialog, _ ->
                val playlistId = editText.text.toString().trim()
                if (playlistId.isNotEmpty()) {
                    loadPlaylistById(activity, playlistId)
                    dialog.dismiss()
                }
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .create()
        dialog.show()
        editText.requestFocus()
    }
    
    private fun loadPlaylistById(activity: MainActivity, playlistId: String) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val apiService = activity.getApiService()
                if (apiService == null) {
                    Log.e(TAG, "API Service not available")
                    return@launch
                }
                
                Log.d(TAG, "📋 Loading playlist with ID: $playlistId")
                generateButton.isEnabled = false
                generateButton.text = "⏳ Loading playlist..."
                
                val tracksResult = apiService.getPlaylistTracks(playlistId)
                val tracks = tracksResult.getOrNull()
                
                if (tracks == null) {
                    Log.e(TAG, "❌ Failed to load playlist")
                    generateButton.isEnabled = true
                    generateButton.text = "🎵 Generate New Playlist"
                    android.widget.Toast.makeText(requireContext(), "Failed to load playlist", android.widget.Toast.LENGTH_SHORT).show()
                    return@launch
                }
                
                Log.d(TAG, "✅ Loaded playlist with ${tracks.size} tracks")
                activity.selectedPlaylistId = playlistId
                generateButton.isEnabled = true
                generateButton.text = "🎵 Generate New Playlist"
                android.widget.Toast.makeText(requireContext(), "Playlist loaded! (${tracks.size} tracks)", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error loading playlist: ${e.message}", e)
                generateButton.isEnabled = true
                generateButton.text = "🎵 Generate New Playlist"
            }
        }
    }
    
    private fun showPlaylistSearchDialog(activity: MainActivity) {
        val editText = android.widget.EditText(requireContext()).apply {
            hint = "Enter playlist name (e.g., Fredrik)"
            setPadding(32, 32, 32, 32)
        }
        
        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setTitle("Search Playlists")
            .setView(editText)
            .setPositiveButton("Search") { dialog, _ ->
                val query = editText.text.toString().trim()
                if (query.isNotEmpty()) {
                    searchPlaylistByName(activity, query)
                    dialog.dismiss()
                }
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .create()
        dialog.show()
        editText.requestFocus()
    }
    
    private fun searchPlaylistByName(activity: MainActivity, query: String) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val apiService = activity.getApiService()
                if (apiService == null) {
                    Log.e(TAG, "API Service not available")
                    return@launch
                }
                
                Log.d(TAG, "🔍 Searching for playlist: '$query'")
                val playlistsResult = apiService.searchPlaylist(query)
                val playlists = playlistsResult.getOrNull() ?: emptyList()
                Log.d(TAG, "✅ Found ${playlists.size} playlists matching '$query'")
                
                if (playlists.isEmpty()) {
                    Log.w(TAG, "⚠️ No playlists found matching '$query'")
                    android.widget.Toast.makeText(requireContext(), "No playlists found", android.widget.Toast.LENGTH_SHORT).show()
                    return@launch
                }
                
                // Show search results in a dialog
                val playlistNames = playlists.map { "${it.name} (${it.trackCount} tracks)" }.toTypedArray()
                
                val dialog = android.app.AlertDialog.Builder(requireContext())
                    .setTitle("Search Results for '$query'")
                    .setSingleChoiceItems(playlistNames, -1) { dialog, which ->
                        activity.selectedPlaylistId = playlists[which].id
                        Log.d(TAG, "✅ Selected playlist: ${playlists[which].name}")
                        dialog.dismiss()
                    }
                    .setNegativeButton("Cancel") { dialog, _ ->
                        dialog.dismiss()
                    }
                    .create()
                dialog.show()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error searching playlists: ${e.message}", e)
            }
        }
    }
}

