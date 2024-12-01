package com.google.projectgameface.utils

/** Word swiped data class **/
data class WordSwiped(private val _word: String, var startTime: Long, var endTime: Long) {
    val TAG = "WordSwiped"

    private val _createdTime: Long = System.currentTimeMillis()
    private var _updatedTime: Long = _createdTime

    var word: String = _word
    var size: Int = word.length
    var duration: Long = endTime - startTime

//    // Doubly linked list
//    var lastWord: WordSwiped? = null
//    var nextWord: WordSwiped? = null
//        // OR
//    // WordSwiped ArrayList

    /** Index in the list of words, `wordsTyped`, in DebuggingStats **/
    var index: Int = -1

    fun updateWord(word: String) {
        this.word = word
        this.startTime = startTime
        this.endTime = endTime
        this.size = word.length
        this.duration = endTime - startTime
    }
}
