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

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
public class CursorControllerTest {
  @Test
  public void getCursorTranslateXY_buildSuccess_returnCursorMoveOffset() {

    CursorController cursorController =
        new CursorController(ApplicationProvider.getApplicationContext(), 0, 0);

    float[] foreheadF1 = {500.f, 500.f};
    assertEquals(cursorController.getCursorTranslateXY(foreheadF1, 100)[0], 500.0f, 0.1);

    // Move a little bit.
    float[] foreheadF2 = {510.f, 510.f};
    assertEquals(cursorController.getCursorTranslateXY(foreheadF2, 100)[0], 426.66666f, 0.000001);
  }
}
