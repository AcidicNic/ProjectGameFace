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

    // TODO: Move all constants here (default settings values and any other hard coded values)

    const val DEFAULT_ANIMATION_DURATION = 1000

    /* Default HeadBoard Settings */
    const val DEFAULT_SMOOTHING = 5
    const val DEFAULT_HEAD_COORD_SCALE_FACTOR_X = 1.5f
    const val DEFAULT_HEAD_COORD_SCALE_FACTOR_Y = 1.5f
    const val DEFAULT_EDGE_HOLD_DURATION = 1000
    const val DEFAULT_DRAG_TOGGLE_DURATION = 300
    const val DEFAULT_PITCH_YAW = true
    const val DEFAULT_NOSE_TIP = true
    const val DEFAULT_REALTIME_SWIPE = true
    const val DEFAULT_DEBUG_SWIPE = false
    const val DEFAULT_DURATION_POP_OUT = true
    const val DEFAULT_DIRECT_MAPPING = true


    const val DEFAULT_UI_FEEDBACK_DELAY = 3 // (D1A)
    const val DEFAULT_QUICK_TAP_THRESHOLD = 2000 // ms - blue sweep time (long tap timeout period)
    const val DEFAULT_LONG_TAP_THRESHOLD = 2500 // ms - yellow sweep time
    const val HOVER_ZONE_RADIUS: Int = 150 // pixels
    const val D1A_DURATION: Int = 500 // ms - initial hover time
}
