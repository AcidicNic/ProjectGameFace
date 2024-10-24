package com.google.projectgameface.Utils;

public class DebuggingStats {
    private static final String TAG = "DebuggingStats";
    private static final String STATS_FILE = "stats.json";

    public Float cpmLatestAvg;
    public Float wpmLatestAvg;

    public Float cpmAvg;
    public Float wpmAvg;
    public Float charsPerPhraseAvg;
    public Float wordsPerPhraseAvg;
    public Float swipeDurationAvg;
}
