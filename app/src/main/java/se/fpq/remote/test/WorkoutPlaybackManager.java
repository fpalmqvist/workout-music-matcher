package se.fpq.remote.test;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages workout timing and track skipping during playback
 * Works with either generated playlists or test playlists
 */
public class WorkoutPlaybackManager {
    private List<WorkoutTrack> tracks = new ArrayList<>();
    private int currentTrackIndex = 0;
    private long workoutStartTime = 0;
    private boolean isRunning = false;
    private long pausedAt = 0;
    private long totalPausedDuration = 0;

    /**
     * Initialize with a list of tracks to play
     */
    public void initializeWithTracks(List<WorkoutTrack> playlistTracks) {
        tracks.clear();
        tracks.addAll(playlistTracks);
    }

    /**
     * Initialize with demo workout tracks for testing
     * Using verified Spotify URIs
     */
    public List<WorkoutTrack> initializeDemoWorkout() {
        tracks.clear();
        
        // Create a demo workout with 3 tracks of 60 seconds each
        // Using verified tracks from Spotify
        WorkoutTrack track1 = new WorkoutTrack(
            "spotify:track:1301WleyT98MSxVHnAlFYp",  // "Blinding Lights" by The Weeknd (verified)
            0,
            60000,
            "Warmup - Blinding Lights",
            null
        );
        
        WorkoutTrack track2 = new WorkoutTrack(
            "spotify:track:0VjIjW4GlUZAMYd2vXMwbU",  // "Levitating" by Dua Lipa (verified)
            60000,
            120000,
            "Main - Levitating",
            null
        );
        
        WorkoutTrack track3 = new WorkoutTrack(
            "spotify:track:4cOdK2wGLETKBW3PvgPWqLv",  // "One Kiss" by Calvin Harris & Dua Lipa (verified)
            120000,
            180000,
            "Cool Down - One Kiss",
            null
        );
        
        tracks.add(track1);
        tracks.add(track2);
        tracks.add(track3);
        
        return new ArrayList<>(tracks);
    }

    public void start() {
        isRunning = true;
        workoutStartTime = System.currentTimeMillis();
        currentTrackIndex = 0;
        totalPausedDuration = 0;
    }

    public void pause() {
        isRunning = false;
        pausedAt = System.currentTimeMillis();
    }

    public void resume() {
        if (!isRunning && pausedAt > 0) {
            totalPausedDuration += System.currentTimeMillis() - pausedAt;
            isRunning = true;
        }
    }

    public void stop() {
        isRunning = false;
        workoutStartTime = 0;
        totalPausedDuration = 0;
        currentTrackIndex = 0;
    }

    /**
     * Get elapsed time since workout started (accounting for pauses)
     */
    public long getElapsedTimeMs() {
        if (workoutStartTime == 0) return 0;
        if (isRunning) {
            return System.currentTimeMillis() - workoutStartTime - totalPausedDuration;
        } else {
            return pausedAt - workoutStartTime - totalPausedDuration;
        }
    }

    /**
     * Check if current track should change and return the next track if so
     */
    public WorkoutTrack checkAndGetNextTrack() {
        if (!isRunning || tracks.isEmpty()) return null;

        long elapsedMs = getElapsedTimeMs();
        
        // Find which track should be playing now
        for (int i = 0; i < tracks.size(); i++) {
            WorkoutTrack track = tracks.get(i);
            if (elapsedMs >= track.startTimeMs && elapsedMs < track.endTimeMs) {
                if (i != currentTrackIndex) {
                    currentTrackIndex = i;
                    return track;
                }
                return null; // Still in current track
            }
        }

        // Workout is over
        if (elapsedMs >= getTotalWorkoutDurationMs()) {
            isRunning = false;
            return null;
        }

        return null;
    }

    public WorkoutTrack getCurrentTrack() {
        if (currentTrackIndex >= 0 && currentTrackIndex < tracks.size()) {
            return tracks.get(currentTrackIndex);
        }
        return null;
    }

    public int getCurrentTrackIndex() {
        return currentTrackIndex;
    }

    public int getTotalTracks() {
        return tracks.size();
    }

    public long getTotalWorkoutDurationMs() {
        if (tracks.isEmpty()) return 0;
        return tracks.get(tracks.size() - 1).endTimeMs;
    }

    public boolean isRunning() {
        return isRunning;
    }

    public boolean isWorkoutComplete() {
        return !isRunning && workoutStartTime > 0 && getElapsedTimeMs() >= getTotalWorkoutDurationMs();
    }

    public float getProgress() {
        long total = getTotalWorkoutDurationMs();
        if (total == 0) return 0;
        return (float) getElapsedTimeMs() / total;
    }

    /**
     * Get time remaining in current track (in seconds)
     */
    public long getTimeRemainingInTrackSeconds() {
        WorkoutTrack current = getCurrentTrack();
        if (current == null) return 0;
        long elapsedMs = getElapsedTimeMs();
        long remaining = current.endTimeMs - elapsedMs;
        return Math.max(0, remaining / 1000);
    }

    /**
     * Get total workout duration in seconds
     */
    public long getTotalWorkoutDurationSeconds() {
        return getTotalWorkoutDurationMs() / 1000;
    }
}
