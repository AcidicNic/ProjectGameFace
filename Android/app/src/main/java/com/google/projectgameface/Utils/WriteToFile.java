package com.google.projectgameface.Utils;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * WriteToFile
 */
public class WriteToFile {

    private static final String TAG = "WriteToFile";

    private Context context;
    private File downloadsFolder;
    private File logFile;
    private File errFile;

    /**
     * Constructor
     * @param context
     */
    public WriteToFile(Context context) {
        this.context = context;

        // Save to external storage (Downloads folder)
        downloadsFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        logFile = new File(downloadsFolder, "gameface.log");
        errFile = new File(downloadsFolder, "gameface-err.log");
    }

    /**
     * Write to log file
     * @param tag
     * @param message
     */
    public void log(String tag, String message) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        long currentTimeMs = System.currentTimeMillis();
        Date currentDateTime = new Date(currentTimeMs);

        writeToLogFile("{" + sdf.format(currentDateTime) + "} [" + tag + "]: " + message, logFile);
        Log.d(tag, message);
    }

    /**
     * Write to err file
     * @param tag
     * @param message
     */
    public void logError(String tag, String message) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        long currentTimeMs = System.currentTimeMillis();
        Date currentDateTime = new Date(currentTimeMs);

        writeToLogFile("{" + sdf.format(currentDateTime) + "} [" + tag + "]: " + message, errFile);
        Log.d(tag, message);
    }

    /**
     * Write to log file
     * @param log
     * @param logFile
     */
    private void writeToLogFile(String log, File logFile) {
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

    /**
     * Get log file
     * @return logFile
     */
    public File getLogFile() {
        return logFile;
    }

    /**
     * Get String from file
     * @return fileContentsStr
     */
    public String getStringFromFile(File file) {
        StringBuilder stringBuilder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                stringBuilder.append(line).append("\n");
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return stringBuilder.toString().trim();
    }

    /**
     * Get err file
     * @return errFile
     */
    public File getErrFile() {
        return errFile;
    }

    /**
     * Clear log file
     */
    public void clearLogFile() {
        if (logFile.exists()) {
            logFile.delete();
        }
    }

    /**
     * Clear err file
     */
    public void clearErrFile() {
        if (errFile.exists()) {
            errFile.delete();
        }
    }

    /**
     * Save an Object to a JSON file with the given filename
     * @param data Object to save
     * @param fileName JSON file name
     * @return true if success, false if fail
     */
    public boolean saveObjToJson(Object data, String fileName) {
        Gson gson = new Gson(); // Add Gson dependency if not already
        String jsonString = gson.toJson(data);

        File jsonFile = new File(downloadsFolder, fileName);

        try (FileWriter file = new FileWriter(jsonFile)) {
            file.write(jsonString);
            return true;
        } catch (IOException e) {
            logError(TAG, "saveObjToJson: " + e.getMessage());
            return false;
        }
    }

    /**
     * Load an Object from a JSON file with the given filename
     * @param fileName JSON file name
     * @param clazz Class of the Object to load
     * @return Object loaded from JSON file
     */
    public Object loadObjFromJson(String fileName, Class<?> clazz) {
        File jsonFile = new File(downloadsFolder, fileName);

        if (!jsonFile.exists()) {
            logError(TAG, "loadObjFromJson - json file path does not exist: " + jsonFile.getPath());
            return null;
        }

        Gson gson = new Gson();
        try (Reader reader = new FileReader(jsonFile)) {
            return gson.fromJson(reader, clazz);
        } catch (IOException e) {
            logError(TAG, "loadObjFromJson: " + e.getMessage());
            return null;
        }
    }
}
