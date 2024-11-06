package com.google.projectgameface.utils

import android.content.Context
import android.util.Log

class DebuggingStats() : TimestampAware {
    val TAG = "DebuggingStats"

    var created: Long = System.currentTimeMillis()
    var lastModified: Long = created

    var cpmLatestAvg: Float = 0.0f
    var wpmLatestAvg: Float = 0.0f

    var cpmAvg: Float = 0.0f
    var wpmAvg: Float = 0.0f

    var charsPerPhraseAvg: Float = 0.0f
    var wordsPerPhraseAvg: Float = 0.0f

    var swipeDurationAvg: Float = 0.0f

    var wordsTyped: ArrayList<WordTyped> = ArrayList()

//    override fun toString(): String {
//        val sb = StringBuilder("${this::class.simpleName}(")
//        val properties = this::class.memberProperties
//        for ((index, property) in properties.withIndex()) {
//            sb.append("${property.name}=${property.get(this)}")
//            if (index < properties.size - 1) {
//                sb.append(", ")
//            }
//        }
//        sb.append(")")
//        return sb.toString()
//    }

    override fun updateTimestamp() {
        lastModified = System.currentTimeMillis()
    }

    fun addWordTyped(word: String, timestamp: Long? = null) {
        Log.d(TAG, "addWordTyped(): $word")

        if (timestamp == null) wordsTyped.add(WordTyped(word))
        else wordsTyped.add(WordTyped(word, timestamp))
    }

    fun save(context: Context) {
        Log.d(TAG, "save(): ...")
        val writeToFile = WriteToFile(context)
        writeToFile.saveObjToJson(this, Config.STATS_FILE)
        Log.d(TAG, "save(): success!")
    }

    fun load(context: Context) {
        Log.d(TAG, "load(): ...")
        Log.d(TAG, "load(): old ${this}")

        val writeToFile = WriteToFile(context)
        val stats = writeToFile.loadObjFromJson(Config.STATS_FILE, DebuggingStats::class.java) as? DebuggingStats
        if (stats == null) {
            Log.d(TAG, "load(): load failed! stats is null")
            return
        }

        created = stats.created
        lastModified = stats.lastModified

        cpmLatestAvg = stats.cpmLatestAvg
        wpmLatestAvg = stats.wpmLatestAvg
        cpmAvg = stats.cpmAvg
        wpmAvg = stats.wpmAvg
        charsPerPhraseAvg = stats.charsPerPhraseAvg
        wordsPerPhraseAvg = stats.wordsPerPhraseAvg
        swipeDurationAvg = stats.swipeDurationAvg

        Log.d(TAG, "load(): success!")
        Log.d(TAG, "load(): new ${this}")
    }
}
