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


import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.util.Log;
import android.view.KeyEvent;

import com.google.projectgameface.utils.CursorUtils;


public class DispatchEventHelper {
  /** Duration for swipe action. Should be fast enough to change page in launcher app. */
  private static final int SWIPE_DURATION_MS = 100;

  /**
   * Helper function to check the event type and dispatch the events desired location.
   * @param parentService Need for calling {@link AccessibilityService#dispatchGesture}.
   * @param cursorController Some event need cursor control.
   * @param serviceUiManager For drawing drag line on canvas.
   * @param event Event to dispatch.
   */
  public static void checkAndDispatchEvent(
      CursorAccessibilityService parentService,
      CursorController cursorController,
      ServiceUiManager serviceUiManager,
      BlendshapeEventTriggerConfig.EventType event,
      KeyEvent keyEvent) {


    int[] cursorPosition = cursorController.getCursorPositionXY();

    int  eventOffsetX = 0;
    int eventOffsetY = 0;


    switch (event) {
      case CONTINUOUS_TOUCH:
        Log.d("dispatchEvent", "continuous touch");
        parentService.continuousTouch(keyEvent);
        break;

      case TOGGLE_TOUCH:
        Log.d("dispatchEvent", "toggle touch");
        if (keyEvent == null || keyEvent.getAction() == KeyEvent.ACTION_DOWN) {
          parentService.toggleTouch();
        }
        break;

      case CURSOR_TOUCH:
        Log.d("dispatchEvent", "Cursor touch");
        if (keyEvent == null || keyEvent.getAction() == KeyEvent.ACTION_DOWN) {
          parentService.quickTap(cursorPosition, 200);
          serviceUiManager.drawTouchDot(cursorController.getCursorPositionXY());
        }
        break;

      case CURSOR_LONG_TOUCH:
        if (keyEvent == null || keyEvent.getAction() == KeyEvent.ACTION_DOWN) {
          parentService.quickTap(cursorPosition, 650);
          serviceUiManager.drawTouchDot(cursorController.getCursorPositionXY());
        }
        break;

      case BEGIN_TOUCH:
        Log.d("dispatchEvent", "start touch");
        if (keyEvent == null || keyEvent.getAction() == KeyEvent.ACTION_DOWN) {
          parentService.startTouch();
        }
        break;

      case END_TOUCH:
        Log.d("dispatchEvent", "end touch");
        if (keyEvent == null || keyEvent.getAction() == KeyEvent.ACTION_DOWN) {
          parentService.endTouch();
        }
        break;

      case CURSOR_PAUSE:
        if (keyEvent == null || keyEvent.getAction() == KeyEvent.ACTION_DOWN) {
          parentService.togglePause();
        }
        break;

      case DELETE_PREVIOUS_WORD:
        if (keyEvent == null || keyEvent.getAction() == KeyEvent.ACTION_DOWN) {
          parentService.deleteLastWord();
        }
        break;

      case CURSOR_RESET:
        if (cursorController.isRealtimeSwipe || cursorController.isDirectMappingEnabled()) {
          break;
        }
        cursorController.resetCursorToCenter(false);
        break;

      case SWIPE_LEFT:
        if (cursorController.isRealtimeSwipe) {
          break;
        }
        parentService.dispatchGesture(
            CursorUtils.createSwipe(
                cursorPosition[0] + eventOffsetX,
                cursorPosition[1] + eventOffsetY,
                /* xOffset= */ -500,
                /* yOffset= */ 0,
                /* duration= */ SWIPE_DURATION_MS),
            /* callback= */ null,
            /* handler= */ null);
        break;

      case SWIPE_RIGHT:
        if (cursorController.isRealtimeSwipe) {
          break;
        }
        parentService.dispatchGesture(
            CursorUtils.createSwipe(
                cursorPosition[0] + eventOffsetX,
                cursorPosition[1] + eventOffsetY,
                /* xOffset= */ 500,
                /* yOffset= */ 0,
                /* duration= */ SWIPE_DURATION_MS),
            /* callback= */ null,
            /* handler= */ null);
        break;

      case SWIPE_UP:
        if (cursorController.isRealtimeSwipe) {
          break;
        }
        parentService.dispatchGesture(
            CursorUtils.createSwipe(
                cursorPosition[0] + eventOffsetX,
                cursorPosition[1] + eventOffsetY,
                /* xOffset= */ 0,
                /* yOffset= */ -500,
                /* duration= */ SWIPE_DURATION_MS),
            /* callback= */ null,
            /* handler= */ null);
        break;

      case SWIPE_DOWN:
        if (cursorController.isRealtimeSwipe) {
          break;
        }
        parentService.dispatchGesture(
            CursorUtils.createSwipe(
                cursorPosition[0] + eventOffsetX,
                cursorPosition[1] + eventOffsetY,
                /* xOffset= */ 0,
                /* yOffset= */ 500,
                /* duration= */ SWIPE_DURATION_MS),
            /* callback= */ null,
            /* handler= */ null);
        break;

      case DRAG_TOGGLE:
        parentService.dispatchDragOrHold();
        break;

      case SWIPE_START:
        if (cursorController.isRealtimeSwipe) {
          break;
        }
        cursorController.startSwipe(cursorPosition[0], cursorPosition[1]);
        break;

      case SWIPE_STOP:
        if (cursorController.isSwiping()) {
          parentService.dispatchGesture(
                  new GestureDescription.Builder()
                          .addStroke(new GestureDescription.StrokeDescription(
                                  cursorController.getSwipePath(), 0, 100))
                          .build(),
                  null,
                  null
          );
          cursorController.stopSwipe();
        }
        break;

      case HOME:
        parentService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
        break;

      case BACK:
        parentService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
        break;

      case SHOW_NOTIFICATION:
        parentService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS);
        break;

      case SHOW_APPS:
        parentService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS);
        break;

      case SMART_TOUCH:
        parentService.smartTouch(keyEvent);
        break;

      default:
    }
  }
}
