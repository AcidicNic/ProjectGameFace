package com.google.projectgameface.utils

import android.content.Context
import android.util.Log

data class DebuggingStats(val name: String) : TimestampAware {
    val TAG = name + "DebuggingStats"
    var version: Int = 1

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
        val newWord = WordSwiped(word, startTime, endTime)

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
        val newSession = Session(startIndex, endIndex)
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

        // Calculate time between words within sessions only
        val totalTimeBetweenWords = sessions.sumOf { session ->
            val sessionWords = wordsSwiped.subList(session.startIndex, session.endIndex)
            sessionWords.zipWithNext { prev, curr ->
                curr.startTime - prev.endTime
            }.sum()
        }

        val totalSessions = sessions.size
        val totalSessionDurations = sessions.sumOf { session ->
            val startTime = wordsSwiped.getOrNull(session.startIndex)?.startTime ?: 0L
            val endTime = wordsSwiped.getOrNull(session.endIndex - 1)?.endTime ?: 0L
            endTime - startTime
        }

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

        // Average time between words (within sessions)
        timeBetweenWordsAvg = if (wordsSwiped.size > 1) {
            (totalTimeBetweenWords / (wordsSwiped.size - sessions.size).toDouble()).toFloat()
        } else 0.0f

        Log.d(
            TAG, "Global stats updated: charsPerMinAvg=$charsPerMinAvg, wordsPerMinAvg=$wordsPerMinAvg, " +
                    "charsPerSessionAvg=$charsPerSessionAvg, wordsPerSessionAvg=$wordsPerSessionAvg, " +
                    "swipeDurationAvg=$swipeDurationAvg, timeBetweenWordsAvg=$timeBetweenWordsAvg"
        )
    }

    fun updateAllSessions() {
        Log.d(TAG, "updateAllSessions(): Starting to update all sessions...")

        // Iterate through all sessions and update them
        sessions.forEachIndexed { index, session ->
            try {
                session.update(this) // Update the session with the current state of DebuggingStats
                Log.d(TAG, "updateAllSessions(): Updated session at index $index")
            } catch (e: Exception) {
                Log.e(TAG, "updateAllSessions(): Error updating session at index $index: ${e.message}")
            }
        }

        // Optionally, recalculate global stats after updating all sessions
        updateGlobalStats()

        Log.d(TAG, "updateAllSessions(): Finished updating all sessions")
    }

    /** Save stats to json file. **/
    fun save(context: Context) {
        Log.d(TAG, "save(): ...")
        val writeToFile = WriteToFile(context)
        writeToFile.saveObjToJson(this, name + Config.STATS_FILE)
        Log.d(TAG, "save(): success!")
    }

    /** Load stats from json file. **/
    fun load(context: Context) {
        Log.d(TAG, "load(): old $this")

        val writeToFile = WriteToFile(context)
        val loadedStats = writeToFile.loadObjFromJson(name + Config.STATS_FILE, DebuggingStats::class.java) as? DebuggingStats

        if (loadedStats == null) {
            Log.d(TAG, "load(): Loaded stats is null")
            return
        }

        // Migrate the loadedStats to ensure compatibility
        migrate(loadedStats)

        // Use reflection to dynamically copy all fields
        val fields = this::class.java.declaredFields
        for (field in fields) {
            try {
                field.isAccessible = true
                val value = field.get(loadedStats)
                field.set(this, value)
            } catch (e: Exception) {
                Log.e(TAG, "load(): Error updating field ${field.name}: ${e.message}")
            }
        }

        Log.d(TAG, "load(): success!")
        Log.d(TAG, "load(): new $this")
    }

    fun wipe() {
        Log.d(TAG, "wipe(): Wiping stats...")
        wordsSwiped.clear()
        sessions.clear()
        updateGlobalStats()
        updateTimestamp()
        Log.d(TAG, "wipe(): success!")
    }

    /** Migrate stats to the latest version. **/
    fun migrate(stats: DebuggingStats) {
        if (stats.version == 0) {
            // recalculate all session and global stats if loading from an old stats file
            stats.updateAllSessions()
            stats.version = 1
        }
        /* Implement migration here if needed in the future */
//        if (stats.version == 1) {
//            // stats.newProperty = calculateFromOldProperties(stats.oldProperty)
//            stats.version = 2
//        }
    }
}
