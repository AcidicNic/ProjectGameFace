package com.google.projectgameface;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager.LayoutParams;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.projectgameface.utils.DebuggingStats;
import com.google.projectgameface.utils.WriteToFile;

import java.util.Objects;

public class DebuggingStatsActivity extends AppCompatActivity {

    private static final String TAG = "DebuggingStats"; // TODO: remove unnecessary "Activity" from the end of the tags.

    private DebuggingStats debuggingStats = new DebuggingStats();
    private WriteToFile writeToFile;

    private TextView content;
    private TextView logTxt;

    private Button logBtn;

    private ScrollView logScrollView;
    private LinearLayout jumpBtnLayout;

    private boolean isLogVisible = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_debugging_stats);
        getWindow().addFlags(LayoutParams.FLAG_KEEP_SCREEN_ON);

        writeToFile = new WriteToFile(this);

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

        // get stats
        debuggingStats = new DebuggingStats();
        debuggingStats.load(this);

        displayStats();

        // btn onclicks
        findViewById(R.id.refreshBtn).setOnClickListener(v -> {
            refreshStats();
        });
        findViewById(R.id.wipeStatsBtn).setOnClickListener(v -> {
            wipeStats();
        });
        findViewById(R.id.wipeLogBtn).setOnClickListener(v -> {
            writeToFile.clearLogFile();
            hideLogFile();
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

    private void wipeStats() {
        debuggingStats = new DebuggingStats();
        debuggingStats.save(this);
        displayStats();
        sendIntentToService("RESET_DEBUGGING_STATS");
    }

    private void refreshStats() {
        debuggingStats.load(this);
        displayStats();
    }

    private void displayStats() {
        String wpmLatestAvgTxt = String.format("Average words per minute of latest phrase: %.1f words/min", debuggingStats.getWpmLatestAvg());
        String wpmAvgTxt = String.format("Average words per minute:  %.1f words/min", debuggingStats.getWpmAvg());
        String wordsPerPhraseAvgTxt = String.format("Average words per phrase:  %.1f words/phrase", debuggingStats.getWordsPerPhraseAvg());
        String swipeDurationAvgTxt = String.format("Average swipe duration:  %.1f ms", debuggingStats.getSwipeDurationAvg());

        content.setText(String.format("%s\n%s\n%s\n%s", wpmLatestAvgTxt, wpmAvgTxt, wordsPerPhraseAvgTxt, swipeDurationAvgTxt));
    }

    private void showLogFile() {
        isLogVisible = true;
        String logStr = writeToFile.getStringFromFile(writeToFile.getLogFile());
        logTxt.setText(logStr);
        logTxt.setVisibility(View.VISIBLE);
        jumpBtnLayout.setVisibility(View.VISIBLE);
        logBtn.setText("Hide Log");
    }

    private void hideLogFile() {
        isLogVisible = false;
        logTxt.setVisibility(View.GONE);
        jumpBtnLayout.setVisibility(View.GONE);
        logBtn.setText("Show Log");
    }

    private void sendIntentToService(String action) {
        Intent intent = new Intent(this, CursorAccessibilityService.class);
        intent.setAction(action);
        startService(intent);
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
