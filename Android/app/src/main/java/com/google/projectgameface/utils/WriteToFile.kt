package com.google.projectgameface.utils

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.gson.Gson
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.FileReader
import java.io.IOException
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

class WriteToFile(private val context: Context) {
    val TAG: String = "WriteToFile"

    private val gson: Gson = Gson()
    val statsDir: File = File(Config.FILES_DIR, Config.STATS_DIR)
    val logsDir: File = File(Config.FILES_DIR, Config.LOGS_DIR)
    var hiddenDir: File = File(logsDir, Config.ARCHIVED_DIR)

    private var logFile: File = File(logsDir, Config.LOG_FILE)
    private var errFile: File = File(logsDir, Config.ERR_LOG_FILE)

    init {
        createDirsAndFiles()
    }

    /**
     * Write to log file
     * @param tag
     * @param message
     */
    fun log(tag: String, message: String) {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val currentTimeMs = System.currentTimeMillis()
        val currentDateTime = Date(currentTimeMs)

        writeToLogFile("{" + sdf.format(currentDateTime) + "} [" + tag + "]: " + message, logFile)
        Log.d(tag, message)
    }

    /**
     * Write to err file
     * @param tag
     * @param message
     */
    fun logError(tag: String, message: String) {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val currentTimeMs = System.currentTimeMillis()
        val currentDateTime = Date(currentTimeMs)

        writeToLogFile("{" + sdf.format(currentDateTime) + "} [" + tag + "]: " + message, errFile!!)
        Log.e(tag, message)
    }

    /**
     * Write to log file
     * @param log
     * @param logFile
     */
    private fun writeToLogFile(log: String, logFile: File) {
        var fos: FileOutputStream? = null
        try {
            fos = FileOutputStream(logFile, true) // Append mode
            fos.write((log + "\n").toByteArray())
            fos.flush()
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            if (fos != null) {
                try {
                    fos.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }
    }

    /**
     * Get log file
     * @return logFile
     */
    fun getLogFile(): File {
        return logFile!!
    }

    /**
     * Get String from file
     * @return fileContentsStr
     */
    fun getStringFromFile(file: File?): String {
        val stringBuilder = StringBuilder()
        try {
            BufferedReader(FileReader(file)).use { reader ->
                var line: String?
                while ((reader.readLine().also { line = it }) != null) {
                    stringBuilder.append(line).append("\n")
                }
            }
        } catch (e: IOException) {
            logError(TAG, "IOException in getStringFromFile(): " + e.message)
            return ""
        }
        return stringBuilder.toString().trim { it <= ' ' }
    }

    /**
     * Get err file
     * @return errFile
     */
    fun getErrFile(): File {
        return errFile!!
    }

    /**
     * Clear log file
     */
    fun clearLogFile(actuallyDelete: Boolean) {
        // No log file exists
        if (!logFile!!.exists()) return

        // Actually delete log file
        if (actuallyDelete) {
            logFile!!.delete()
            return
        }

        if (!hiddenDir.exists()) {
            hiddenDir.mkdirs()
        }

        // Move log file to hidden dir
        val archivedLogFile = File(hiddenDir, getCurrentDateTimeStr() + "-gameface.log")
        logFile!!.renameTo(archivedLogFile)

        // Ensure the original logFile can be reused
        if (!logFile!!.exists()) {
            logFile!!.createNewFile()
        }
    }

    /**
     * Clear err file
     */
    fun clearErrFile(actuallyDelete: Boolean) {
        // No log file exists
        if (!errFile!!.exists()) return

        // Actually delete log file
        if (actuallyDelete) {
            errFile!!.delete()
            return
        }

        if (!hiddenDir.exists()) {
            hiddenDir.mkdirs()
        }

        // Move log file to hidden dir
        val archivedLogFile = File(hiddenDir, getCurrentDateTimeStr() + "-gameface-err.log")
        errFile!!.renameTo(archivedLogFile)

        // Ensure the original logFile can be reused
        if (!errFile!!.exists()) {
            errFile!!.createNewFile()
        }
    }

    /**
     * Save an Object to a JSON file with the given filename
     * @param data Object to save
     * @param fileName JSON file name
     * @return true if success, false if fail
     */
    fun saveObjToJson(data: Any?, fileName: String): Boolean {
        val jsonString = gson.toJson(data)
        val jsonFile = File(statsDir, fileName)

        // Ensure the parent directory exists
        jsonFile.parentFile?.let {
            if (!it.exists())
                it.mkdirs()
        }

        // Create the file if it doesn't exist
        if (jsonFile.createNewFile())
            log(TAG, "saveObjToJson: File created: " + jsonFile.path)
        else
            log(TAG, "saveObjToJson: File already exists: " + jsonFile.path)

        try {
            // Write the JSON data to the file
            jsonFile.outputStream().use { output ->
                output.write(jsonString.toByteArray())
            }
            return true
        } catch (e: Exception) {
            logError(TAG, "saveObjToJson: " + e.message)
            return false
        }
    }

    /**
     * Load an Object from a JSON file with the given filename
     * @param fileName JSON file name
     * @param clazz Class of the Object to load
     * @return Object loaded from JSON file
     */
    fun loadObjFromJson(fileName: String, clazz: Class<*>?): Any? {
        val jsonFile = File(statsDir, fileName)

        if (!jsonFile.exists()) {
            logError(TAG,"loadObjFromJson - json file path does not exist: " + jsonFile.path)
            return null
        }

        try {
            FileReader(jsonFile).use { reader ->
                return gson!!.fromJson(reader, clazz)
            }
        } catch (e: IOException) {
            logError(TAG, "loadObjFromJson: " + e.message)
            return null
        }
    }

    fun getCurrentDateTimeStr(): String {
        val current = LocalDateTime.now()
        val formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
        return current.format(formatter)
    }

    fun saveBitmap(bitmap: Bitmap) {
        val imageFile: File = File(Config.FILES_DIR, "screenshot-${getCurrentDateTimeStr()}.png")
        try {
            FileOutputStream(imageFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (e: IOException) {
            logError(TAG, "saveBitmap(): " + e.message)
            e.printStackTrace()
        }
    }

    fun createDirsAndFiles() {
        // Create directories if they don't exist
        if (!statsDir.exists()) {
            statsDir.mkdirs()
        }
        if (!logsDir.exists()) {
            logsDir.mkdirs()
        }
        if (!hiddenDir.exists()) {
            hiddenDir.mkdirs()
        }

        // Create log files if they don't exist
        if (!logFile.exists()) {
            try {
                logFile.createNewFile()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        if (!errFile.exists()) {
            try {
                errFile.createNewFile()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }
}
