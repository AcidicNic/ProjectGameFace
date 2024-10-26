package com.google.projectgameface.utils

import android.graphics.Point

class SwipePoint(x: Int, y: Int, var timestamp: Long) : Point(x, y) {
    override fun toString(): String {
        return "($x, $y) @ $timestamp"
    }
}