package com.google.projectgameface.Utils;

import android.content.Context;

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

    public void save(Context context) {
        WriteToFile writeToFile = new WriteToFile(context);
        writeToFile.saveObjToJson(this, STATS_FILE);
    }

    public void load(Context context) {
        WriteToFile writeToFile = new WriteToFile(context);
        DebuggingStats stats = (DebuggingStats) writeToFile.loadObjFromJson(STATS_FILE, DebuggingStats.class);
        if (stats != null) {
            cpmLatestAvg = stats.cpmLatestAvg;
            wpmLatestAvg = stats.wpmLatestAvg;
            cpmAvg = stats.cpmAvg;
            wpmAvg = stats.wpmAvg;
            charsPerPhraseAvg = stats.charsPerPhraseAvg;
            wordsPerPhraseAvg = stats.wordsPerPhraseAvg;
            swipeDurationAvg = stats.swipeDurationAvg;
        }
    }
}
