package com.google.projectgameface.utils

import android.content.Context
import android.os.Environment
import android.util.Log
import com.google.gson.Gson
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

class WriteToFile(private val context: Context) {
    val TAG: String = "WriteToFile"

    private val gson: Gson
    private val downloadsDir: File
    private var hiddenDir: File?
    private var logFile: File
    private var errFile: File

    init {
        gson = Gson()
        downloadsDir =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        hiddenDir = File(downloadsDir, Config.HIDDEN_DIR)
        if (!hiddenDir!!.exists()) {
            if (!hiddenDir!!.mkdirs()) hiddenDir = null
        }
        logFile = File(downloadsDir, Config.LOG_FILE)
        errFile = File(downloadsDir, Config.ERR_LOG_FILE)
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
        Log.d(tag, message)
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
            throw RuntimeException(e)
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
    fun clearLogFile() {
        // no log file exists
        if (!logFile!!.exists()) return

        // hidden dir does not exist
        if (hiddenDir == null) {
            logFile!!.delete()
            return
        }

        // move log file to hidden dir
        logFile!!.renameTo(File(hiddenDir, getCurrentDateTimeStr() + "gameface.log"))
    }

    /**
     * Clear err file
     */
    fun clearErrFile() {
        if (errFile!!.exists()) {
            errFile!!.delete()
        }
    }

    /**
     * Save an Object to a JSON file with the given filename
     * @param data Object to save
     * @param fileName JSON file name
     * @return true if success, false if fail
     */
    fun saveObjToJson(data: Any?, fileName: String?): Boolean {
        val jsonString = gson!!.toJson(data)

        val jsonFile = File(downloadsDir, fileName)

        try {
            FileWriter(jsonFile).use { file ->
                file.write(jsonString)
                return true
            }
        } catch (e: IOException) {
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
    fun loadObjFromJson(fileName: String?, clazz: Class<*>?): Any? {
        val jsonFile = File(downloadsDir, fileName)

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

}