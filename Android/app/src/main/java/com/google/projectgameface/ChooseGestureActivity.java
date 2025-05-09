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

import android.util.Log;
import android.view.MenuItem;
import android.view.WindowManager.LayoutParams;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;

public class ChooseGestureActivity extends AppCompatActivity {

    private static final String TAG = "ChooseGestureActivity";
    private LinearLayout unFocus;

    private static final String CURRENT_TEXT = "\n(Current)";
    private static final String IN_USE_TEXT = "\n(In use)";
    private static final String UNAVAILABLE_TEXT = "\n(Unavailable)";

    private ArrayList<Integer> viewIds = new ArrayList<>(Arrays.asList(
        R.id.openMouth,
        R.id.mouthLeft,
        R.id.mouthRight,
        R.id.rollLowerMouth,
        R.id.raiseRightEyebrow,
        R.id.raiseLeftEyebrow,
        R.id.lowerRightEyebrow,
        R.id.lowerLeftEyebrow,
        R.id.keyOne,
        R.id.keyTwo,
        R.id.keyThree,
        R.id.swipeFromRightKbd,
        R.id.none
    ));

    private LinearLayout openMouth;
    private LinearLayout mouthLeft;
    private LinearLayout mouthRight;
    private LinearLayout rollLowerMouth;
    private LinearLayout raiseRightEyebrow;
    private LinearLayout raiseLeftEyebrow;
    private LinearLayout lowerRightEyebrow;
    private LinearLayout lowerLeftEyebrow;
    private LinearLayout keyOne;
    private LinearLayout keyTwo;
    private LinearLayout keyThree;
    private LinearLayout swipeFromRightKbd;
    private LinearLayout none;

    // What is the target action for this page.
    BlendshapeEventTriggerConfig.EventType pageEventType;

    // What button should be pre-selected when user first open the page.
    int preSelectButtonIndex;

    private BlendshapeEventTriggerConfig.Blendshape selectedBlendshape;

    /**
     * Set gesture box as "In use" state by dim the color, change text
     * and disable clickable.
     * @param oneGestureBox LinearLayout item contain gesture box.
     */
    private void changeButtonStyleToInUse(View oneGestureBox){
        if (!(oneGestureBox instanceof ViewGroup))
        {return;}

        ViewGroup oneGestureBoxViewGroup = (ViewGroup) oneGestureBox;
        oneGestureBoxViewGroup.setClickable(false);
        oneGestureBoxViewGroup.setAlpha(0.3f);

        TextView gestureText = oneGestureBoxViewGroup.findViewWithTag("text_view_gesture_name");

        if (gestureText == null)
        {return;}


        String newTextLabel = (String) gestureText.getText();
        newTextLabel= newTextLabel.replace(IN_USE_TEXT, "");
        newTextLabel = newTextLabel.replace(CURRENT_TEXT, "");
        newTextLabel = newTextLabel.replace(UNAVAILABLE_TEXT, "");
        newTextLabel +=  IN_USE_TEXT;


        gestureText.setText(newTextLabel);
        gestureText.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
    }


    /**
     * Set gesture box as "In use" state by dim the color, change text
     * and disable clickable.
     * @param oneGestureBox LinearLayout item contain gesture box.
     */
    private void changeButtonStyleToUnavailable(View oneGestureBox){
        if (!(oneGestureBox instanceof ViewGroup))
        {return;}

        ViewGroup oneGestureBoxViewGroup = (ViewGroup) oneGestureBox;
        oneGestureBoxViewGroup.setClickable(false);
        oneGestureBoxViewGroup.setAlpha(0.3f);

        TextView gestureText = oneGestureBoxViewGroup.findViewWithTag("text_view_gesture_name");

        if (gestureText == null)
        {return;}


        String newTextLabel = (String) gestureText.getText();
        newTextLabel= newTextLabel.replace(IN_USE_TEXT, "");
        newTextLabel = newTextLabel.replace(CURRENT_TEXT, "");
        newTextLabel = newTextLabel.replace(UNAVAILABLE_TEXT, "");
        newTextLabel += UNAVAILABLE_TEXT;


        gestureText.setText(newTextLabel);
        gestureText.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
    }

    /**
     * Set gesture box as "Current" state by change text
     * @param oneGestureBox LinearLayout item contain gesture box.
     */
    private void changeButtonStyleToCurrent(View oneGestureBox){
        if (!(oneGestureBox instanceof ViewGroup))
        {return;}

        ViewGroup oneGestureBoxViewGroup = (ViewGroup) oneGestureBox;
        TextView gestureText = oneGestureBoxViewGroup.findViewWithTag("text_view_gesture_name");
        oneGestureBoxViewGroup.setClickable(true);
        oneGestureBoxViewGroup.setAlpha(1.f);

        if (gestureText == null)
        {return;}

        String newTextLabel = (String) gestureText.getText();
        newTextLabel = newTextLabel.replace(IN_USE_TEXT, "");
        newTextLabel = newTextLabel.replace(CURRENT_TEXT, "");
        newTextLabel = newTextLabel.replace(UNAVAILABLE_TEXT, "");
        newTextLabel +=  CURRENT_TEXT;
        gestureText.setText(newTextLabel);
        gestureText.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
    }


    private void resetButtonStyle(View oneGestureBox){
        if (!(oneGestureBox instanceof ViewGroup))
        {return;}

        ViewGroup oneGestureBoxViewGroup = (ViewGroup) oneGestureBox;
        TextView gestureText = oneGestureBoxViewGroup.findViewWithTag("text_view_gesture_name");
        oneGestureBoxViewGroup.setClickable(true);
        oneGestureBoxViewGroup.setAlpha(1.f);

        if (gestureText == null)
        {return;}

        String newTextLabel = (String) gestureText.getText();
        newTextLabel = newTextLabel.replace(IN_USE_TEXT, "");
        newTextLabel = newTextLabel.replace(CURRENT_TEXT, "");
        newTextLabel = newTextLabel.replace(UNAVAILABLE_TEXT, "");


        gestureText.setText(newTextLabel);
        gestureText.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
    }



    /**
     * Check every face gesture button
     * and disable the already used gesture.
     * @param pageEventType What event action setting page we are in.
     * This is the action show at the page title
     */
    private void checkGestureButtonInUse(BlendshapeEventTriggerConfig.EventType pageEventType){

        String profileName = ProfileManager.getCurrentProfile(this);
        SharedPreferences preferences = getSharedPreferences(profileName, Context.MODE_PRIVATE);

        // For each face gesture button.
        for (int gestureButtonIndexInUi = 0; gestureButtonIndexInUi < viewIds.size(); gestureButtonIndexInUi++) {
            LinearLayout oneGestureBox = findViewById(viewIds.get(gestureButtonIndexInUi));

            resetButtonStyle(oneGestureBox);

            // Check each possible action from local config.
            for (BlendshapeEventTriggerConfig.EventType checkingEventType : BlendshapeEventTriggerConfig.EventType.values())
            {
                // Load binding config.
                int boundGestureIndex = preferences.getInt(checkingEventType.toString(),
                    BlendshapeEventTriggerConfig.BLENDSHAPE_FROM_ORDER_IN_UI.indexOf(BlendshapeEventTriggerConfig.Blendshape.NONE));
                BlendshapeEventTriggerConfig.Blendshape boundedBlendshape = BlendshapeEventTriggerConfig.BLENDSHAPE_FROM_ORDER_IN_UI
                    .get(boundGestureIndex);

                if (boundGestureIndex != gestureButtonIndexInUi)
                {
                    continue;
                }

                // Assign more text information on the button.
                if (pageEventType == checkingEventType)
                {
                    changeButtonStyleToCurrent(oneGestureBox);
                }
                else
                {
                    if (boundedBlendshape != BlendshapeEventTriggerConfig.Blendshape.NONE)
                    {
                        changeButtonStyleToInUse(oneGestureBox);
                    }
                }
                break;
                }
        }

        // None button case need special handling.
        ViewGroup noneGestureBox = findViewById(viewIds.get(BlendshapeEventTriggerConfig.BLENDSHAPE_FROM_ORDER_IN_UI.indexOf(BlendshapeEventTriggerConfig.Blendshape.NONE)));
        int boundGestureIndex = preferences.getInt(pageEventType.toString(), BlendshapeEventTriggerConfig.BLENDSHAPE_FROM_ORDER_IN_UI.indexOf(BlendshapeEventTriggerConfig.Blendshape.NONE));
        if (boundGestureIndex == BlendshapeEventTriggerConfig.BLENDSHAPE_FROM_ORDER_IN_UI.indexOf(BlendshapeEventTriggerConfig.Blendshape.NONE)) {
            changeButtonStyleToCurrent(noneGestureBox);
        }

        // Swiping gestures require special handling.
        ViewGroup swipeFromRightKbdBox = findViewById(viewIds.get(BlendshapeEventTriggerConfig.BLENDSHAPE_FROM_ORDER_IN_UI.indexOf(BlendshapeEventTriggerConfig.Blendshape.SWIPE_FROM_RIGHT_KBD)));
        if (!Boolean.TRUE.equals(BlendshapeEventTriggerConfig.EVENT_TYPE_SHOULD_SHOW_SWIPING_INPUTS.get(pageEventType))) {
            changeButtonStyleToUnavailable(swipeFromRightKbdBox);
        }
    }


    /**
     * Make back button work as back action in device's navigation.
     * @param item The menu item that was selected.
     */
    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
      if (item.getItemId() == android.R.id.home) {
        finish();
        return true;
      }
      return super.onOptionsItemSelected(item);

    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_choose_input);
        getWindow().addFlags(LayoutParams.FLAG_KEEP_SCREEN_ON);

        String profileName = ProfileManager.getCurrentProfile(this);
        SharedPreferences preferences = getSharedPreferences(profileName, Context.MODE_PRIVATE);

        pageEventType = (BlendshapeEventTriggerConfig.EventType) getIntent().getSerializableExtra("eventType");

        if (pageEventType == null)
        {
            Log.e(TAG, "Start intent with invalid extra EventType.");
            finish();
            return;
        }
        Log.i(TAG, "onCreate: " + pageEventType);

        String pageDescription = BlendshapeEventTriggerConfig.getActionDescription(this, pageEventType);
        ((TextView)findViewById(R.id.actionDescriptionText)).setText(pageDescription);


        // Setting actionbar
        String actionBarText = BlendshapeEventTriggerConfig.BEATIFY_EVENT_TYPE_NAME.get(pageEventType);
        Objects.requireNonNull(getSupportActionBar()).setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setTitle(actionBarText);



        preSelectButtonIndex = preferences.getInt(pageEventType.toString(),
                BlendshapeEventTriggerConfig.BLENDSHAPE_FROM_ORDER_IN_UI.indexOf(BlendshapeEventTriggerConfig.Blendshape.NONE));


        findViewById(R.id.refreshBtn).setOnClickListener(v -> {
            Intent intentBack = new Intent(getBaseContext(), CursorBinding.class);
            startActivity(intentBack);
            finish();

        });


        findViewById(R.id.nextBtn).setOnClickListener(v -> {
            if (selectedBlendshape == BlendshapeEventTriggerConfig.Blendshape.NONE ||
                selectedBlendshape == BlendshapeEventTriggerConfig.Blendshape.SWITCH_ONE ||
                selectedBlendshape == BlendshapeEventTriggerConfig.Blendshape.SWITCH_TWO ||
                selectedBlendshape == BlendshapeEventTriggerConfig.Blendshape.SWITCH_THREE ||
                selectedBlendshape == BlendshapeEventTriggerConfig.Blendshape.SWIPE_FROM_RIGHT_KBD
            ) {
                // Write config to sharedpref.
                BlendshapeEventTriggerConfig.writeBindingConfig(getBaseContext(),
                    selectedBlendshape,
                    pageEventType,
                    0);
                try {
                    CharSequence text = "Setting Completed!";
                    int duration = Toast.LENGTH_LONG;
                    Toast toast = Toast.makeText(getBaseContext(), text, duration);
                    toast.show();
                } catch (Exception e) {
                    Log.i(TAG, e.toString());
                }

                Intent intentBack = new Intent(getBaseContext(), CursorBinding.class);
                intentBack.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intentBack);
                finish();
            } else {
                Intent intentGoGestureSize = new Intent(getBaseContext(), GestureSizeActivity.class);
                intentGoGestureSize.putExtra("eventType", pageEventType);
                intentGoGestureSize.putExtra("selectedGesture", selectedBlendshape);
                intentGoGestureSize.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intentGoGestureSize);
            }
        });
        setupUi();
    }


    private void setupUi(){
        for (int i = 0; i < viewIds.size(); i++) {
            final int position = i;
            View childView = findViewById(viewIds.get(i));

            childView.setBackgroundResource(R.drawable.gesture_button);

            if(preSelectButtonIndex == i) {
                selectedBlendshape = BlendshapeEventTriggerConfig.BLENDSHAPE_FROM_ORDER_IN_UI.get(preSelectButtonIndex);
                childView.setBackgroundResource(R.drawable.gesture_button_selected);
                unFocus = (LinearLayout) childView;
            }

            childView.setOnClickListener(v -> {
                childView.setBackgroundResource(R.drawable.gesture_button_selected);
                if(unFocus != null && (unFocus != childView)){
                    unFocus.setBackgroundResource(R.drawable.gesture_button);
                }
                selectedBlendshape = BlendshapeEventTriggerConfig.BLENDSHAPE_FROM_ORDER_IN_UI.get(position);
                if(selectedBlendshape == BlendshapeEventTriggerConfig.Blendshape.NONE){
                    Button nextBtn = findViewById(R.id.nextBtn);
                    nextBtn.setText("Done");
                } else {
                    Button nextBtn = findViewById(R.id.nextBtn);
                    nextBtn.setText("Next");
                }
                unFocus = (LinearLayout) childView;

            });
        }
        checkGestureButtonInUse(pageEventType);
    }

    @Override
    protected void onResume() {
        super.onResume();
        setupUi();
    }

}
