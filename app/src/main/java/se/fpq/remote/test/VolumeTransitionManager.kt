package se.fpq.remote.test

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log

private const val TAG = "VolumeTransition"

/**
 * Manages smooth volume transitions (fades) when switching between tracks
 * Uses Android AudioManager to fade out/in system music volume for smooth transitions
 */
class VolumeTransitionManager(private val context: Context) {
    
    companion object {
        // Fade configuration
        const val FADE_DURATION_MS = 800  // 0.8 seconds total fade (400ms out + 400ms in)
        const val FADE_INTERVAL_MS = 50L  // Update volume every 50ms
    }
    
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    private var originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    
    private var fadeOutRunnable: Runnable? = null
    private var fadeInRunnable: Runnable? = null
    
    /**
     * Fade out current track and fade in next track
     * @param durationMs total fade duration (default 800ms)
     * @param onSwitchTrack callback to switch to next track (called during fade-out)
     */
    fun fadeOutThenIn(durationMs: Int = FADE_DURATION_MS, onSwitchTrack: () -> Unit) {
        Log.d(TAG, "🔊 Starting fade transition (${durationMs}ms)...")
        
        val fadeDuration = durationMs / 2 // Split between fade-out and fade-in
        originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        
        // Cancel any existing fade operations
        cancelFade()
        
        // Fade out current track
        fadeOut(fadeDuration) {
            Log.d(TAG, "   ✓ Fade-out complete, switching track...")
            
            // Switch track during fade-out
            onSwitchTrack()
            
            // Fade in new track after a brief delay to ensure playback started
            handler.postDelayed({
                fadeIn(fadeDuration) {
                    Log.d(TAG, "   ✓ Fade-in complete, volume restored")
                }
            }, 100)
        }
    }
    
    /**
     * Fade out volume gradually
     * @param durationMs fade duration in milliseconds
     * @param onComplete callback when fade is complete
     */
    private fun fadeOut(durationMs: Int, onComplete: () -> Unit) {
        val steps = (durationMs / FADE_INTERVAL_MS).toInt()
        val volumeDecrement = originalVolume.toFloat() / steps
        var currentStep = 0
        
        fadeOutRunnable = object : Runnable {
            override fun run() {
                if (currentStep < steps) {
                    val newVolume = (originalVolume - (volumeDecrement * currentStep)).toInt()
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
                    Log.d(TAG, "   🔉 Volume: $newVolume/$maxVolume")
                    
                    currentStep++
                    handler.postDelayed(this, FADE_INTERVAL_MS)
                } else {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                    Log.d(TAG, "   🔇 Fade-out finished")
                    onComplete()
                }
            }
        }
        
        fadeOutRunnable?.let { handler.post(it) }
    }
    
    /**
     * Fade in volume gradually
     * @param durationMs fade duration in milliseconds
     * @param onComplete callback when fade is complete
     */
    private fun fadeIn(durationMs: Int, onComplete: () -> Unit) {
        val steps = (durationMs / FADE_INTERVAL_MS).toInt()
        val volumeIncrement = originalVolume.toFloat() / steps
        var currentStep = 0
        
        fadeInRunnable = object : Runnable {
            override fun run() {
                if (currentStep < steps) {
                    val newVolume = (volumeIncrement * currentStep).toInt()
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
                    Log.d(TAG, "   🔊 Volume: $newVolume/$maxVolume")
                    
                    currentStep++
                    handler.postDelayed(this, FADE_INTERVAL_MS)
                } else {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
                    Log.d(TAG, "   🔊 Fade-in finished (volume restored to $originalVolume/$maxVolume)")
                    onComplete()
                }
            }
        }
        
        fadeInRunnable?.let { handler.post(it) }
    }
    
    /**
     * Cancel any ongoing fade operations and restore volume
     */
    fun cancelFade() {
        fadeOutRunnable?.let { handler.removeCallbacks(it) }
        fadeInRunnable?.let { handler.removeCallbacks(it) }
        fadeOutRunnable = null
        fadeInRunnable = null
        // Restore original volume
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
        Log.d(TAG, "   ⏹️  Fade cancelled, volume restored")
    }
    
    /**
     * Cleanup resources
     */
    fun release() {
        cancelFade()
    }
}

