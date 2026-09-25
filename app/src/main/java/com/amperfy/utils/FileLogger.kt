/*
 * Amperfy4Android - an unofficial Android port of Amperfy
 * Copyright (c) 2026 angelo
 * Based on Amperfy for iOS, Copyright (c) 2019-2025 Maximilian Bauer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.amperfy.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

/**
 * 文件日志记录器
 *
 * 将日志写入应用的外部文件目录，便于在电脑上查看
 * 日志文件位置: Android/data/<applicationId>/files/logs/
 */
object FileLogger {

    private const val TAG = "FileLogger"
    private const val LOG_DIR = "logs"
    private const val MAX_LOG_FILE_SIZE = 10 * 1024 * 1024 // 10MB
    private const val MAX_LOG_FILES = 5 // 保留最多5个日志文件
    private const val PREF_NAME = "file_logger_prefs"
    private const val KEY_ENABLED = "file_logging_enabled"

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileNameDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    @Volatile
    private var logDir: File? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var isEnabled: Boolean = false

    /**
     * 初始化文件日志系统
     * 应该在Application onCreate时调用
     */
    fun init(context: Context) {
        try {
            appContext = context.applicationContext

            // 读取启用状态（默认关闭）
            val prefs = appContext!!.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            isEnabled = prefs.getBoolean(KEY_ENABLED, false)

            // 使用外部文件目录，不需要运行时权限
            // 路径: /storage/emulated/0/Android/data/<applicationId>/files/logs/
            val externalFilesDir = context.getExternalFilesDir(null)
            if (externalFilesDir != null) {
                logDir = File(externalFilesDir, LOG_DIR).apply {
                    if (!exists()) {
                        mkdirs()
                    }
                }
                Log.d(TAG, "Log directory initialized: ${logDir?.absolutePath}")
                Log.d(TAG, "File logging enabled: $isEnabled")

                // 清理旧的日志文件
                cleanOldLogFiles()
            } else {
                Log.e(TAG, "External files directory is null")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize log directory", e)
        }
    }

    /**
     * 启用文件日志记录
     */
    fun enable() {
        isEnabled = true
        saveEnabledState(true)
        Log.d(TAG, "File logging ENABLED")

        // 立即写入一条日志确认功能已启用
        try {
            val dir = logDir
            if (dir == null) {
                Log.e(TAG, "Log directory is NULL!")
                return
            }

            if (!dir.exists()) {
                dir.mkdirs()
            }

            val logFile = getCurrentLogFile(dir)
            val timestamp = dateFormat.format(Date())
            val logEntry = "[$timestamp] [$TAG] ========== FILE LOGGING ENABLED ==========\n"

            FileWriter(logFile, true).use { writer ->
                writer.append(logEntry)
                writer.flush()
            }

            Log.d(TAG, "Test log written to: ${logFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write enable log", e)
        }
    }

    /**
     * 禁用文件日志记录
     */
    fun disable() {
        // 先写入禁用日志（此时isEnabled还是true）
        try {
            val dir = logDir
            if (dir != null && dir.exists()) {
                val logFile = getCurrentLogFile(dir)
                val timestamp = dateFormat.format(Date())
                val logEntry = "[$timestamp] [$TAG] ========== FILE LOGGING DISABLED ==========\n"

                FileWriter(logFile, true).use { writer ->
                    writer.append(logEntry)
                    writer.flush()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write disable log", e)
        }

        isEnabled = false
        saveEnabledState(false)
        Log.d(TAG, "File logging DISABLED")
    }

    /**
     * 检查文件日志记录是否启用
     */
    fun isEnabled(): Boolean = isEnabled

    /**
     * 保存启用状态到SharedPreferences
     */
    private fun saveEnabledState(enabled: Boolean) {
        try {
            val context = appContext ?: return
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save enabled state", e)
        }
    }

    /**
     * 写入日志到文件
     */
    fun log(tag: String, message: String) {
        if (!isEnabled) return // 如果未启用，直接返回

        try {
            val dir = logDir
            if (dir == null || !dir.exists()) {
                Log.w(TAG, "Log directory not available")
                return
            }

            val logFile = getCurrentLogFile(dir)
            val timestamp = dateFormat.format(Date())
            val logEntry = "[$timestamp] [$tag] $message\n"

            // 追加写入文件
            FileWriter(logFile, true).use { writer ->
                writer.append(logEntry)
                writer.flush()
            }

            // 检查文件大小，如果超过限制则轮转
            if (logFile.length() > MAX_LOG_FILE_SIZE) {
                rotateLogFile()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write log to file", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error writing log", e)
        }
    }

    /**
     * 写入多行日志（保持格式）
     */
    fun logMultiLine(tag: String, message: String) {
        if (!isEnabled) return // 如果未启用，直接返回

        try {
            val dir = logDir
            if (dir == null || !dir.exists()) {
                return
            }

            val logFile = getCurrentLogFile(dir)
            val timestamp = dateFormat.format(Date())

            // 为多行消息添加时间戳和标签
            val lines = message.lines()
            val formattedMessage = buildString {
                append("[$timestamp] [$tag]\n")
                lines.forEach { line ->
                    append("  $line\n")
                }
                append("\n")
            }

            FileWriter(logFile, true).use { writer ->
                writer.append(formattedMessage)
                writer.flush()
            }

            if (logFile.length() > MAX_LOG_FILE_SIZE) {
                rotateLogFile()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write multi-line log", e)
        }
    }

    /**
     * 获取当前日志文件
     */
    private fun getCurrentLogFile(dir: File): File {
        val today = fileNameDateFormat.format(Date())
        return File(dir, "amperfy_log_$today.txt")
    }

    /**
     * 轮转日志文件（当文件过大时）
     */
    private fun rotateLogFile() {
        try {
            val dir = logDir ?: return
            val currentFile = getCurrentLogFile(dir)
            val timestamp = SimpleDateFormat("HHmmss", Locale.US).format(Date())
            val rotatedFile = File(dir, "${currentFile.nameWithoutExtension}_$timestamp.txt")

            if (currentFile.renameTo(rotatedFile)) {
                Log.d(TAG, "Log file rotated to: ${rotatedFile.name}")
                cleanOldLogFiles()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rotate log file", e)
        }
    }

    /**
     * 清理旧的日志文件，只保留最新的几个
     */
    private fun cleanOldLogFiles() {
        try {
            val dir = logDir ?: return
            val logFiles = dir.listFiles { file ->
                file.isFile && file.name.startsWith("amperfy_log_") && file.name.endsWith(".txt")
            } ?: return

            if (logFiles.size > MAX_LOG_FILES) {
                // 按最后修改时间排序
                val sortedFiles = logFiles.sortedByDescending { it.lastModified() }

                // 删除旧文件
                sortedFiles.drop(MAX_LOG_FILES).forEach { file ->
                    if (file.delete()) {
                        Log.d(TAG, "Deleted old log file: ${file.name}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clean old log files", e)
        }
    }

    /**
     * 获取日志目录路径（用于显示给用户）
     */
    fun getLogDirectoryPath(): String? {
        return logDir?.absolutePath
    }

    /**
     * 获取所有日志文件
     */
    fun getLogFiles(): List<File> {
        val dir = logDir ?: return emptyList()
        return dir.listFiles { file ->
            file.isFile && file.name.startsWith("amperfy_log_") && file.name.endsWith(".txt")
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /**
     * 清空所有日志文件
     */
    fun clearAllLogs() {
        try {
            getLogFiles().forEach { file ->
                file.delete()
            }
            Log.d(TAG, "All log files cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear log files", e)
        }
    }
}
