package com.google.projectgameface.utils

import android.view.KeyEvent

object Config {
    const val FILES_DIR: String = "/data/data/com.google.projectgameface/files"
    const val STATS_DIR: String = "stats/"
    const val LOGS_DIR: String = "logs/"
    const val ARCHIVED_DIR: String = ".archived/"

    const val LOG_FILE: String = "gameface.log"
    const val ERR_LOG_FILE: String = "gameface-err.log"
    const val STATS_FILE: String = "stats.json"

    const val DEBUG: Boolean = true
    const val TIME_BETWEEN_WORDS: Long = 5000

    const val STATS_VERSION: Int = 1

    @JvmField
    val VALID_KEY_EVENT_KEYS: Set<Int> = setOf(
        KeyEvent.KEYCODE_1,
        KeyEvent.KEYCODE_2,
        KeyEvent.KEYCODE_3,
        KeyEvent.KEYCODE_4
    )
}
