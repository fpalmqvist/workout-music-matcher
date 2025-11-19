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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WorkoutSelectionFragment : Fragment() {
    companion object {
        private const val TAG = "WorkoutSelection"
    }

    private lateinit var savedWorkoutsContainer: LinearLayout
    
    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                val inputStream = requireContext().contentResolver.openInputStream(uri)
                if (inputStream != null) {
                    val result = ZwoWorkoutParser.parse(inputStream)
                    result.onSuccess { workout: Workout ->
                        // Save the workout file
                        saveWorkoutFile(uri, workout)
                        // Navigate to playlist generation
                        val activity = requireActivity() as MainActivity
                        activity.showPlaylistGenerationFragment(workout)
                    }
                    result.onFailure { error ->
                        Log.e(TAG, "Parse error: ${error.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading workout: ${e.message}")
            }
        }
    }

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
                text = "Select Workout"
                textSize = 28f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 32
                }
            })

            // Saved workouts section
            addView(TextView(requireContext()).apply {
                text = "Saved Workouts"
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 12
                }
            })

            // ScrollView for saved workouts
            val scrollView = ScrollView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                ).apply {
                    bottomMargin = 16
                }

                savedWorkoutsContainer = LinearLayout(requireContext()).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 0, 0, 0)
                }
                addView(savedWorkoutsContainer)
            }
            addView(scrollView)

            // Import button
            addView(Button(requireContext()).apply {
                text = "📁 Import New Workout"
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 16
                }
                setOnClickListener { filePicker.launch("*/*") }  // File picker will check for .zwo extension
                setBackgroundColor(android.graphics.Color.parseColor("#03DAC6"))
            })
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadSavedWorkouts()
    }

    private fun loadSavedWorkouts() {
        val workoutFiles = requireContext().cacheDir.listFiles { file ->
            file.name.endsWith(".zwo")
        } ?: emptyArray()

        savedWorkoutsContainer.removeAllViews()

        if (workoutFiles.isEmpty()) {
            savedWorkoutsContainer.addView(TextView(requireContext()).apply {
                text = "No saved workouts yet"
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 16
                }
                textSize = 14f
            })
            return
        }

        workoutFiles.forEach { file ->
            try {
                val result = ZwoWorkoutParser.parse(file.inputStream())
                result.onSuccess { workout: Workout ->
                    val button = Button(requireContext()).apply {
                        text = file.nameWithoutExtension
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = 8
                        }
                        setBackgroundColor(android.graphics.Color.parseColor("#6200EE"))
                        setTextColor(android.graphics.Color.WHITE)
                        setOnClickListener {
                            val activity = requireActivity() as MainActivity
                            activity.showPlaylistGenerationFragment(workout)
                        }
                    }
                    savedWorkoutsContainer.addView(button)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading workout: ${e.message}")
            }
        }
    }

    private fun saveWorkoutFile(uri: android.net.Uri, workout: Workout) {
        try {
            val inputStream = requireContext().contentResolver.openInputStream(uri)
            if (inputStream != null) {
                // Get filename from URI
                val fileName = getFileNameFromUri(uri) ?: "workout_${System.currentTimeMillis()}.zwo"
                
                // Ensure it ends with .zwo
                val cleanFileName = if (fileName.endsWith(".zwo")) fileName else "$fileName.zwo"
                
                val cacheFile = File(requireContext().cacheDir, cleanFileName)
                
                // Copy file to cache
                inputStream.use { input ->
                    cacheFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                
                Log.d(TAG, "✅ Saved workout: $cleanFileName")
                
                // Refresh the list
                loadSavedWorkouts()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving workout: ${e.message}")
        }
    }

    private fun getFileNameFromUri(uri: android.net.Uri): String? {
        return try {
            val cursor = requireContext().contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                it.moveToFirst()
                it.getString(nameIndex)
            }
        } catch (e: Exception) {
            null
        }
    }
}

