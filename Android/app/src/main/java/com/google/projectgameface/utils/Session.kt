package com.google.projectgameface.utils


data class Session(var startIndex: Int, var endIndex: Int) {
    private val _createdTime: Long = System.currentTimeMillis()
    private var _updatedTime: Long = _createdTime

    /** Session characters per minute average. **/
    var cpmAvg: Float = 0.0f

    /** Session words per minute average. **/
    var wpmAvg: Float = 0.0f

    /** Session swipe duration average. **/
    var swipeDurationAvg: Float = 0.0f

    /** Session average time between words swiped. **/
    var timeBetweenWordsAvg: Float = 0.0f

    fun update(debuggingStats: DebuggingStats) {
        // Update the end time of the session
        _updatedTime = System.currentTimeMillis()

        // Filter words in the session range
        val sessionWords = debuggingStats.wordsSwiped.subList(startIndex, endIndex)

        // Calculate average time between words
        val timeDifferences = sessionWords.zipWithNext { previous, current ->
            current.startTime - previous.endTime
        }

        // Calculate total characters typed in the session
        val totalCharacters = sessionWords.sumOf { it.word.length }

        // Calculate total duration of the session in minutes
        val sessionDurationMinutes = ((sessionWords.lastOrNull()?.endTime ?: 0L) -
                (sessionWords.firstOrNull()?.startTime ?: 0L)) / 60000.0

        // Calculate CPM (Characters Per Minute)
        cpmAvg = if (sessionDurationMinutes > 0) {
            (totalCharacters / sessionDurationMinutes).toFloat()
        } else 0.0f

        // Calculate WPM (Words Per Minute)
        wpmAvg = if (sessionDurationMinutes > 0) {
            (sessionWords.size / sessionDurationMinutes).toFloat()
        } else 0.0f

        // Calculate average swipe duration
        swipeDurationAvg = if (sessionWords.isNotEmpty()) {
            sessionWords.map { it.duration }.average().toFloat()
        } else 0.0f

        // Calculate average time between words
        timeBetweenWordsAvg = if (timeDifferences.isNotEmpty()) {
            timeDifferences.average().toFloat()
        } else 0.0f
    }
}
