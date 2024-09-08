/*
 * Copyright 2024 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.projectgameface;


import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import androidx.core.content.ContextCompat;

/** The camera overlay of camera feed. */
public class CameraBoxOverlay extends View {

    private static final int DEBUG_TEXT_LOC_X = 2;
    private static final int DEBUG_TEXT_LOC_Y = 250;

    /** White dot on user head. */
    private float whiteDotX = -100.f;
    private float whiteDotY = -100.f;

    /** Misc dot */
    private float otherDotX = -100.f;
    private float otherDotY = -100.f;

    private String topText = "";
    private String bottomText = "";
    private String pauseIndicatorText = "";

    private Paint paint;
    private Paint redPaint;
    private Paint ltGreenPaint;

    public CameraBoxOverlay(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        paint = new Paint();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(ContextCompat.getColor(getContext(), android.R.color.white));
        paint.setTextSize(32);

        redPaint = new Paint();
        redPaint.setStyle(Paint.Style.FILL);
        redPaint.setColor(ContextCompat.getColor(getContext(), android.R.color.holo_red_light));
        redPaint.setTextSize(29);

        ltGreenPaint = new Paint();
        ltGreenPaint.setStyle(Paint.Style.FILL);
        ltGreenPaint.setColor(ContextCompat.getColor(getContext(), android.R.color.holo_green_light));
        ltGreenPaint.setTextSize(32);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawCircle(whiteDotX, whiteDotY, 5, paint);
        canvas.drawCircle(otherDotX, otherDotY, 5, ltGreenPaint);
        canvas.drawText(topText, DEBUG_TEXT_LOC_X, DEBUG_TEXT_LOC_Y, redPaint);
        canvas.drawText(bottomText, DEBUG_TEXT_LOC_X, DEBUG_TEXT_LOC_Y + 50, redPaint);
        canvas.drawText(pauseIndicatorText, DEBUG_TEXT_LOC_X, DEBUG_TEXT_LOC_Y + 100, paint);
    }

    public void setWhiteDot(float x, float y) {
        whiteDotX = x;
        whiteDotY = y;
        invalidate();
    }

    public void setOtherDot(float x, float y) {
        otherDotX = x;
        otherDotY = y;
        invalidate();
    }

    public void setOverlayInfo(String top, String bottom) {
//        topText = "pre: " + top + " ms";
//        bottomText = "med: " + bottom + " ms";
        topText = top;
        bottomText = bottom;
        invalidate();
    }

    public void setPauseIndicator(boolean isPause) {
        if (isPause) {
            topText = "";
            bottomText = "";
            pauseIndicatorText = "pause";
        } else {
            pauseIndicatorText = "";
        }
    }
}