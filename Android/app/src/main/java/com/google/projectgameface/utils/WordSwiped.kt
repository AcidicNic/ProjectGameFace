package com.google.projectgameface.utils

/** Word swiped data class **/
data class WordSwiped(var word: String, var startTime: Long, var endTime: Long) {
    private val _createdTime: Long = System.currentTimeMillis()
    private var _updatedTime: Long = _createdTime

    var size: Int = word.length
    var duration: Long = endTime - startTime

    /** Index in the list of words, `wordsTyped`, in DebuggingStats **/
//    var index: Int = -1

    fun updateWord(word: String) {
        this.word = word
        this.startTime = startTime
        this.endTime = endTime
        this.size = word.length
        this.duration = endTime - startTime
    }
}
