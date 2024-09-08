package com.google.projectgameface;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class LogToFileHelper {

    private static final String LOG_TAG = "CustomLog";
    private Context context;
    private File logFile;
    private File errFile;

    public LogToFileHelper(Context context) {
        this.context = context;

        // Save to external storage (Downloads folder)
        File downloadsFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        logFile = new File(downloadsFolder, "gameface.log");
        errFile = new File(downloadsFolder, "gameface-err.log");
    }

    public void log(String tag, String message) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        long currentTimeMs = System.currentTimeMillis();
        Date currentDateTime = new Date(currentTimeMs);

        writeToFile("{" + sdf.format(currentDateTime) + "} [" + tag + "]: " + message, logFile);
        Log.d(tag, message);
    }

    public void logError(String tag, String message) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        long currentTimeMs = System.currentTimeMillis();
        Date currentDateTime = new Date(currentTimeMs);

        writeToFile("{" + sdf.format(currentDateTime) + "} [" + tag + "]: " + message, errFile);
        Log.d(tag, message);
    }

    private void writeToFile(String log, File logFile) {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(logFile, true); // Append mode
            fos.write((log + "\n").getBytes());
            fos.flush();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public File getLogFile() {
        return logFile; // Returns the log file for further use
    }
}
