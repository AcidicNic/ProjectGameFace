package com.google.projectgameface;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager.LayoutParams;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresPermission;
import androidx.appcompat.app.AppCompatActivity;

import com.google.projectgameface.Utils.WriteToFile;

import java.util.Objects;

public class DebuggingStatsActivity extends AppCompatActivity {

    private static final String TAG = "DebuggingStats"; // TODO: remove unnecessary "Activity" from the end of the tags.

    private WriteToFile writeToFile = new WriteToFile(this);

    private TextView content;
    private TextView logTxt;

    private Button logBtn;

    private ScrollView logScrollView;
    private LinearLayout jumpBtnLayout;

    private float wpmLatestAvg;
    private float wpmAvg;
    private float wordsPerPhraseAvg;
    private float swipeDurationAvg;

    private boolean isLogVisible = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_debugging_stats);
        getWindow().addFlags(LayoutParams.FLAG_KEEP_SCREEN_ON);

        String profileName = ProfileManager.getCurrentProfile(this);
        SharedPreferences preferences = getSharedPreferences(profileName, Context.MODE_PRIVATE);

        // actionbar setup
        Objects.requireNonNull(getSupportActionBar()).setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setTitle("Debugging Stats");

        // textviews
        content = findViewById(R.id.content);
        logTxt = findViewById(R.id.logTxt);
        logBtn = findViewById(R.id.viewLogBtn);

        // layout views
        jumpBtnLayout = findViewById(R.id.jumpBtnRow);
        logScrollView = findViewById(R.id.logScrollView);

        // get stats from shared preferences
        wpmLatestAvg = preferences.getFloat("wpmLatestAvg", 0);
        wpmAvg = preferences.getFloat("wpmAvg", 0);
        wordsPerPhraseAvg = preferences.getFloat("wordsPerPhraseAvg", 0);
        swipeDurationAvg = preferences.getFloat("swipeDurationAvg", 0);

        updateStats();

        // btn onclicks
        findViewById(R.id.refreshBtn).setOnClickListener(v -> {
            refreshStats();
        });
        findViewById(R.id.resetBtn).setOnClickListener(v -> {
            resetStats();
        });
        findViewById(R.id.jumpTopBtn).setOnClickListener(v -> {
            logScrollView.scrollTo(0, 0);
        });
        findViewById(R.id.jumpBottomBtn).setOnClickListener(v -> {
            logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
        });
        logBtn.setOnClickListener(v -> {
            if (isLogVisible) hideLogFile();
            else showLogFile();
        });
    }

    private void sendValueToService(String configName, float value) {
        String profileName = ProfileManager.getCurrentProfile(this);
        SharedPreferences preferences = getSharedPreferences(profileName, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putFloat(configName, value);
        editor.apply();
        Intent intent = new Intent("LOAD_SHARED_CONFIG_BASIC");
        intent.putExtra("configName", configName);
        sendBroadcast(intent);
    }

    private void resetStats() {
        sendValueToService("wpmLatestAvg", 0);
        sendValueToService("wpmAvg", 0);
        sendValueToService("wordsPerPhraseAvg", 0);
        sendValueToService("swipeDurationAvg", 0);
        sendBroadcast(new Intent("RESET_DEBUGGING_STATS"));
        refreshStats();
    }

    private void refreshStats() {
        String profileName = ProfileManager.getCurrentProfile(this);
        SharedPreferences preferences = getSharedPreferences(profileName, Context.MODE_PRIVATE);
        // get stats from shared preferences
        wpmLatestAvg = preferences.getFloat("wpmLatestAvg", 0);
        wpmAvg = preferences.getFloat("wpmAvg", 0);
        wordsPerPhraseAvg = preferences.getFloat("wordsPerPhraseAvg", 0);
        swipeDurationAvg = preferences.getFloat("swipeDurationAvg", 0);
//        phraseLengthAvg = preferences.getFloat("phraseLengthAvg", 0);

        // set textviews
        updateStats();
    }

    private void updateStats() {
        String wpmLatestAvgTxt = String.format("Average words per minute of latest phrase: %.1f words/min", wpmLatestAvg);
        String wpmAvgTxt = String.format("Average words per minute:  %.1f words/min", wpmAvg);
        String wordsPerPhraseAvgTxt = String.format("Average words per phrase:  %.1f words/phrase", wordsPerPhraseAvg);
        String swipeDurationAvgTxt = String.format("Average swipe duration:  %.1f ms", swipeDurationAvg);

        content.setText(String.format("%s\n%s\n%s\n%s", wpmLatestAvgTxt, wpmAvgTxt, wordsPerPhraseAvgTxt, swipeDurationAvgTxt));
    }

    private void showLogFile() {
        isLogVisible = true;
        WriteToFile writeToFile = new WriteToFile(this);
        String logStr = writeToFile.getStringFromFile(writeToFile.getLogFile());
        logTxt.setText(logStr);
        logTxt.setVisibility(View.VISIBLE);
        jumpBtnLayout.setVisibility(View.VISIBLE);
        logBtn.setText("Hide Log");
    }

    private void hideLogFile() {
        isLogVisible = false;
        WriteToFile writeToFile = new WriteToFile(this);
        logTxt.setVisibility(View.GONE);
        jumpBtnLayout.setVisibility(View.GONE);
        logBtn.setText("Show Log");
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
}
