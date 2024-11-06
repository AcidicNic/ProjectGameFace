package com.google.projectgameface.utils

data class WordTyped(private val _word: String, var timestamp: Long = System.currentTimeMillis()) : TimestampAware {
    val TAG = "WordTyped"

    var word: String = _word
    var lastModified: Long = timestamp

    fun length(): Int {
        return word.length
    }

    override fun updateTimestamp() {
        lastModified = System.currentTimeMillis()
    }
}
