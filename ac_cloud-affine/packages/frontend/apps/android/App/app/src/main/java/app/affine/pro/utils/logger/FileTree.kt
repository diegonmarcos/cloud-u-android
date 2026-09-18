package app.affine.pro.utils.logger

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// DE-CLOUDED (#469): this log tree writes to a local file only. The upstream
// version uploaded old logs to Firebase Storage; there is no cloud backend
// here, so the upload path is gone.
class FileTree(context: Context) : Timber.Tree() {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val logDirectory: File = File(context.filesDir, "logs")
    private val currentLogFile: File

    init {
        if (!logDirectory.exists()) {
            logDirectory.mkdirs()
        }
        val today = dateFormat.format(Date())
        currentLogFile = File(logDirectory, "$today.log")
        if (!currentLogFile.exists()) {
            try {
                currentLogFile.createNewFile()
            } catch (e: IOException) {
                Timber.e(e, "Create log file fail.")
            }
        }
    }

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (priority < Log.INFO) {
            return
        }

        val level = when (priority) {
            Log.ASSERT -> "[assert]"
            Log.ERROR -> "[error]"
            Log.WARN -> "[warn]"
            else -> "[info]"
        }
        val log = StringBuilder(level)
            .append(tag?.let { "[$it]" } ?: "")
            .append(" ")
            .append(message)
            .toString()

        MainScope().launch {
            try {
                val timestamp =
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
                val logMessage = "[$timestamp] $log\n"
                withContext(Dispatchers.IO) {
                    FileOutputStream(currentLogFile, true).use {
                        it.write(logMessage.toByteArray())
                        t?.stackTraceToString()?.let { stacktrace ->
                            it.write(stacktrace.toByteArray())
                        }
                    }
                }
            } catch (e: IOException) {
                Timber.e(e, "Failed to write to log file")
            }
        }

    }

    companion object {
        fun get() = Timber.forest().filterIsInstance<FileTree>().firstOrNull()
    }
}
