package se.fpq.remote.test;

/**
 * Represents a single track in a workout sequence
 */
public class WorkoutTrack {
    public String trackUri;
    public int startTimeMs;
    public int endTimeMs;
    public String blockName;
    public Integer targetCadence;
    public Integer bpm;

    public WorkoutTrack(String trackUri, int startTimeMs, int endTimeMs, String blockName, Integer targetCadence) {
        this(trackUri, startTimeMs, endTimeMs, blockName, targetCadence, null);
    }

    public WorkoutTrack(String trackUri, int startTimeMs, int endTimeMs, String blockName, Integer targetCadence, Integer bpm) {
        this.trackUri = trackUri;
        this.startTimeMs = startTimeMs;
        this.endTimeMs = endTimeMs;
        this.blockName = blockName;
        this.targetCadence = targetCadence;
        this.bpm = bpm;
    }

    public int getDurationMs() {
        return endTimeMs - startTimeMs;
    }

    @Override
    public String toString() {
        return blockName + " (" + (startTimeMs / 1000) + "s - " + (endTimeMs / 1000) + "s)";
    }
}

