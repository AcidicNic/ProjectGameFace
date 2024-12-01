package com.google.projectgameface.utils

import android.content.Context
import android.util.Log

data class DebuggingStats(val name: String) : TimestampAware {
    val TAG = "DebuggingStats"

    var created: Long = System.currentTimeMillis()
    var lastModified: Long = created

    var charsPerMinAvg: Float = 0.0f
    var wordsPerMinAvg: Float = 0.0f

    var charsPerSessionAvg: Float = 0.0f
    var wordsPerSessionAvg: Float = 0.0f

    var swipeDurationAvg: Float = 0.0f
    var timeBetweenWordsAvg: Float = 0.0f

    var wordsSwiped: ArrayList<WordSwiped> = ArrayList()
    var sessions: ArrayList<Session> = ArrayList()

    override fun updateTimestamp() {
        lastModified = System.currentTimeMillis()
    }

    fun addWordSwiped(word: String, startTime: Long, endTime: Long) {
        Log.d(TAG, "addWordTyped(word=$word, startTime=$startTime, endTime=$endTime)")

        // Create the new WordSwiped
        val newWord = WordSwiped(word, startTime, endTime).apply {
            index = wordsSwiped.size // Index is the current size of wordsSwiped
        }

        // Add new word to wordsSwiped
        wordsSwiped.add(newWord)

        // Handle sessions
        if (wordsSwiped.size == 1) {
            // If this is the first word, create the first session
            createSession(0, 1)
        } else {
            // Get the previous word
            val previousWord = wordsSwiped[wordsSwiped.size - 2]

            // Check if a new session needs to be created
            if (startTime - previousWord.endTime > Config.TIME_BETWEEN_WORDS) {
                // Create a new session starting at the current word
                createSession(wordsSwiped.size - 1, wordsSwiped.size)
            } else {
                // Update the last session's endIndex
                val lastSession = sessions.last()
                lastSession.endIndex = wordsSwiped.size
            }
        }

        // Update the current session
        updateSession(sessions.size - 1)

        // Update global stats
        updateGlobalStats()

        // Update the timestamp
        updateTimestamp()
    }

    fun createSession(startIndex: Int, endIndex: Int) {
        Log.d(TAG, "createSession(startIndex=$startIndex, endIndex=$endIndex)")
        val newSession = Session(startIndex, endIndex).apply {
            index = sessions.size // Assign the index based on the current size of sessions
        }
        sessions.add(newSession)
    }

    fun updateSession(sessionIndex: Int) {
        Log.d(TAG, "updateSession(sessionIndex=$sessionIndex)")
        sessions[sessionIndex].update(this)
    }

    fun updateGlobalStats() {
        val totalChars = wordsSwiped.sumOf { it.word.length }
        val totalWords = wordsSwiped.size
        val totalSwipeDuration = wordsSwiped.sumOf { it.duration }
        val totalTimeBetweenWords = wordsSwiped.zipWithNext { prev, curr -> curr.startTime - prev.endTime }.sum()

        val totalSessions = sessions.size
        val totalSessionDurations = sessions.sumOf { it.endIndex - it.startIndex }

        // Average characters per minute
        charsPerMinAvg = if (totalSessionDurations > 0) {
            (totalChars / (totalSessionDurations / 60000.0)).toFloat()
        } else 0.0f

        // Average words per minute
        wordsPerMinAvg = if (totalSessionDurations > 0) {
            (totalWords / (totalSessionDurations / 60000.0)).toFloat()
        } else 0.0f

        // Average characters per session
        charsPerSessionAvg = if (totalSessions > 0) {
            (totalChars / totalSessions.toFloat())
        } else 0.0f

        // Average words per session
        wordsPerSessionAvg = if (totalSessions > 0) {
            (totalWords / totalSessions.toFloat())
        } else 0.0f

        // Average swipe duration
        swipeDurationAvg = if (wordsSwiped.isNotEmpty()) {
            (totalSwipeDuration / wordsSwiped.size.toDouble()).toFloat()
        } else 0.0f

        // Average time between words
        timeBetweenWordsAvg = if (wordsSwiped.size > 1) {
            (totalTimeBetweenWords / (wordsSwiped.size - 1).toDouble()).toFloat()
        } else 0.0f

        Log.d(TAG, "Global stats updated: charsPerMinAvg=$charsPerMinAvg, wordsPerMinAvg=$wordsPerMinAvg, charsPerSessionAvg=$charsPerSessionAvg, wordsPerSessionAvg=$wordsPerSessionAvg, swipeDurationAvg=$swipeDurationAvg, timeBetweenWordsAvg=$timeBetweenWordsAvg")
    }

    fun save(context: Context) {
        Log.d(TAG, "save(): ...")
        val writeToFile = WriteToFile(context)
        writeToFile.saveObjToJson(this, name + Config.STATS_FILE)
        Log.d(TAG, "save(): success!")
    }

    fun load(context: Context) {
        Log.d(TAG, "load(): ...")
        Log.d(TAG, "load(): old ${this}")

        val writeToFile = WriteToFile(context)
        val stats = writeToFile.loadObjFromJson(name + Config.STATS_FILE, DebuggingStats::class.java) as? DebuggingStats
        if (stats == null) {
            Log.d(TAG, "load(): load failed! stats is null")
            return
            charsPerMinAvg = 0.0f
            wordsPerMinAvg = 0.0f

            charsPerSessionAvg = 0.0f
            wordsPerSessionAvg = 0.0f

            swipeDurationAvg = 0.0f
            timeBetweenWordsAvg = 0.0f

            wordsSwiped = ArrayList<WordSwiped>()
            sessions = ArrayList<Session>()
        }

        created = stats.created
        lastModified = stats.lastModified

        charsPerMinAvg = stats.charsPerMinAvg
        wordsPerMinAvg = stats.wordsPerMinAvg
        charsPerSessionAvg = stats.charsPerSessionAvg
        wordsPerSessionAvg = stats.wordsPerSessionAvg
        swipeDurationAvg = stats.swipeDurationAvg

        wordsSwiped = stats.wordsSwiped
        sessions = stats.sessions

        Log.d(TAG, "load(): success!")
        Log.d(TAG, "load(): new ${this}")
    }
}
