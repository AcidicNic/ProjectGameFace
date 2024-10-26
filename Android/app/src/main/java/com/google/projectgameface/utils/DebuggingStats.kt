package com.google.projectgameface.utils

import android.content.Context
import android.util.Log

class DebuggingStats {
    val TAG = "DebuggingStats"

    var cpmLatestAvg: Float = 0.0f
    var wpmLatestAvg: Float = 0.0f

    var cpmAvg: Float = 0.0f
    var wpmAvg: Float = 0.0f

    var charsPerPhraseAvg: Float = 0.0f
    var wordsPerPhraseAvg: Float = 0.0f

    var swipeDurationAvg: Float = 0.0f

    fun save(context: Context) {
        Log.d(TAG, "save(): ...")
        val writeToFile = WriteToFile(context)
        writeToFile.saveObjToJson(this, Config.STATS_FILE)
        Log.d(TAG, "save(): success!")
    }

    fun load(context: Context) {
        Log.d(TAG, "load(): ...")

        val writeToFile = WriteToFile(context)
        val stats = writeToFile.loadObjFromJson(Config.STATS_FILE, DebuggingStats::class.java) as? DebuggingStats
        if (stats == null) {
            Log.d(TAG, "load(): load failed! stats is null")
            return
        }

        cpmLatestAvg = stats.cpmLatestAvg
        wpmLatestAvg = stats.wpmLatestAvg
        cpmAvg = stats.cpmAvg
        wpmAvg = stats.wpmAvg
        charsPerPhraseAvg = stats.charsPerPhraseAvg
        wordsPerPhraseAvg = stats.wordsPerPhraseAvg
        swipeDurationAvg = stats.swipeDurationAvg

        Log.d(TAG, "load(): success!")
    }
}
