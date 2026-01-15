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
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

/**
 * The drag cursor of cursor service.
 */
public class FullScreenCanvas extends View {

    private int holdRadius = 75;
    private float drawX = 500.f;
    private float drawY = 500.f;

    private final Paint touchCirclePaint;
    private final Paint dragLinePaint;
    private final Paint holdCirclePaint;
    private Paint activeRegionPaint = new Paint() {{
        setStyle(Paint.Style.STROKE);
        setColor(Color.GREEN);
        setStrokeWidth(2);
    }};
    private Paint activeRegionEdgeHoldPaint = new Paint() {{
        setStyle(Paint.Style.STROKE);
        setColor(Color.parseColor("#FFA500")); // Orange color
        setStrokeWidth(3);
    }};
    private Rect activeRegionBorder = null;
    private boolean isEdgeHoldActive = false;

    private final Paint trailPaint;
    private final Paint opacityPaint;

    private boolean isShowingDrag = false;
    private float dragStartX = 0;
    private float dragStartY = 0;
    private float dragEndX = 0;
    private float dragEndY = 0;

    private boolean isShowingTouch = false;
    private CursorController cursorController;

    private boolean showRect = false;
    private Rect rectCoords;
    private Bitmap previewBitmap = null;
    private Rect previewRegion = null;


    public FullScreenCanvas(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);

        touchCirclePaint = new Paint();
        touchCirclePaint.setStyle(Paint.Style.FILL);
        touchCirclePaint.setColor(Color.parseColor("#04DE71"));

        dragLinePaint = new Paint();
        dragLinePaint.setStrokeWidth(15);
        dragLinePaint.setColor(Color.parseColor("#04DE71"));

        holdCirclePaint = new Paint();
        holdCirclePaint.setStyle(Paint.Style.STROKE);
        holdCirclePaint.setColor(Color.parseColor("#4285f4"));
        holdCirclePaint.setStrokeWidth(10);

        trailPaint = new Paint();
        trailPaint.setStrokeWidth(15);
        trailPaint.setColor(Color.parseColor("#04DE71"));

        opacityPaint = new Paint();
        opacityPaint.setAlpha(128);
    }

    public void initialize(CursorController cursorController) {
        this.cursorController = cursorController;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (previewBitmap != null && previewRegion != null) {
            canvas.drawBitmap(previewBitmap, null, previewRegion, opacityPaint);
        }

        if (isShowingTouch) {
            canvas.drawCircle(drawX, drawY, 25, touchCirclePaint);
            isShowingTouch = false;
            postInvalidateDelayed(30);
        }

        if (isShowingDrag) {
            canvas.drawLine(dragStartX, dragStartY, dragEndX, dragEndY, dragLinePaint);
            canvas.drawCircle(dragStartX, dragStartY, holdRadius, holdCirclePaint);
        }

//        if (cursorController != null && cursorController.isSwiping) {
//            drawCursorTrail(canvas);
//        }

        if (activeRegionBorder != null) {
            // Use edge hold paint (orange) when timer is active, otherwise use normal paint (green)
            Paint paintToUse = isEdgeHoldActive ? activeRegionEdgeHoldPaint : activeRegionPaint;
            canvas.drawRect(activeRegionBorder, paintToUse);
        }
    }

    public void drawTouchCircle(float x, float y) {
        drawX = x;
        drawY = y;
        invalidate();
        isShowingTouch = true;
    }

    public void setDragLineStart(float x, float y) {
        dragStartX = x;
        dragStartY = y;
        // Initialize dragEnd to the same position as start to prevent jump from previous drag
        dragEndX = x;
        dragEndY = y;
        isShowingDrag = true;
        invalidate();
    }

    public void updateDragLine(float x, float y) {
        dragEndX = x;
        dragEndY = y;
        invalidate();
    }

    public void clearDragLine() {
        isShowingDrag = false;
        invalidate();
    }

//    private void drawCursorTrail(Canvas canvas) {
//        List<float[]> cursorTrail = cursorController.getCursorTrail();
//        if (cursorTrail.size() < 2) return;
//
//        for (int i = 0; i < cursorTrail.size() - 1; i++) {
//            float[] point1 = cursorTrail.get(i);
//            float[] point2 = cursorTrail.get(i + 1);
//            canvas.drawLine(point1[0], point1[1], point2[0], point2[1], trailPaint);
//        }
//        canvas.drawCircle(cursorTrail.get(cursorTrail.size() - 1)[0], cursorTrail.get(cursorTrail.size() - 1)[1], holdRadius, holdCirclePaint);
//        invalidate();
//    }

    /**
     * Set draw radius size for hold action.
     * @param holdRadius
     */
    public void setHoldRadius(float holdRadius) {
        this.holdRadius = (int) holdRadius;
    }

    public void setRect(Rect rect) {
        rectCoords = rect;
    }

    public void setPreviewBitmap(Bitmap bitmap, Rect region) {
        Log.d("FullScreenCanvas", "Setting preview bitmap");
        previewBitmap = bitmap;
        previewRegion = region;
        invalidate();
    }

    public void clearPreviewBitmap() {
        Log.d("FullScreenCanvas", "Clearing preview bitmap");
        previewBitmap = null;
        previewRegion = null;
        invalidate();
    }

    public void setActiveCursorRegion(Rect region) {
        Log.d("FullScreenCanvas", "updateActiveCursorRegion()");
        activeRegionBorder = region;
        invalidate();
    }

    public void setEdgeHoldActive(boolean isActive) {
        if (isEdgeHoldActive != isActive) {
            isEdgeHoldActive = isActive;
            invalidate();
        }
    }
}
