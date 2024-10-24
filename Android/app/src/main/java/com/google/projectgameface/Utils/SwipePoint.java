package com.google.projectgameface.Utils;

import android.graphics.Point;

public class SwipePoint extends Point {
    public long timestamp;

    public SwipePoint(int x, int y, long timestamp) {
        super(x, y);
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ") @ " + timestamp;
    }
}