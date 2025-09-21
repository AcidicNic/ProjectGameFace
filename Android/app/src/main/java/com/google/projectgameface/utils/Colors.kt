package com.google.projectgameface.utils

import android.graphics.Color
import androidx.core.graphics.toColorInt

enum class Colors {
    RED,
    ORANGE,
    YELLOW,
    GREEN,
    BLUE,
    WHITE;

    companion object {
        fun fromString(color: String): Colors {
            return when (color.lowercase()) {
                "red" -> RED
                "orange" -> ORANGE
                "yellow" -> YELLOW
                "green" -> GREEN
                "blue" -> BLUE
                "white" -> WHITE
                else -> throw IllegalArgumentException("Unknown color: $color")
            }
        }
    }

    fun cursorFill(): Int {
        return when (this) {
            RED -> "#D9FF5722".toColorInt()
            ORANGE -> "#D9FF9800".toColorInt()
            YELLOW -> "#D9FFEB3B".toColorInt()
            GREEN -> "#D94CAF50".toColorInt()
            BLUE -> "#D92196F3".toColorInt()
            WHITE -> "#D9FFFFFF".toColorInt()
        }
    }
    fun cursorOutline(): Int {
        return when (this) {
            RED -> "#FF5722".toColorInt()
            ORANGE -> "#FF9800".toColorInt()
            YELLOW -> "#FFEB3B".toColorInt()
            GREEN -> "#4CAF50".toColorInt()
            BLUE -> "#2196F3".toColorInt()
            WHITE -> "#FFFFFF".toColorInt()
        }
    }
}
