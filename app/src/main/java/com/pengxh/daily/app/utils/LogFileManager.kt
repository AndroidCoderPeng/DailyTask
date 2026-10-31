package com.pengxh.daily.app.utils

import android.content.Context
import android.os.Environment
import android.util.Log
import com.pengxh.kt.lite.extensions.timestampToCompleteDate
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.locks.ReentrantLock
import java.util.stream.Collectors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object LogFileManager {
    private val kTag = "LogFileManager"
    private const val MAX_LOG_SIZE = 5 * 1024 * 1024 // 5MB
    private const val MAX_LOG_FILES = 5 // 最多保留5个日志文件
    private const val LOG_FILE_PREFIX = "app_runtime_log"
    private const val LOG_FILE_SUFFIX = ".txt"
    private lateinit var currentLogFile: Path
    private lateinit var logDirectory: Path
    private val fileLock = ReentrantLock() // 防止并发写入冲突

    @Synchronized
    fun initLogFile(context: Context) {
        val documentDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: throw IllegalStateException("External storage directory not available")
        logDirectory = documentDir.toPath()
        currentLogFile = logDirectory.resolve("app_runtime_log.txt")
        try {
            if (!Files.exists(currentLogFile)) {
                Files.createFile(currentLogFile)
            } else if (Files.size(currentLogFile) > MAX_LOG_SIZE) {
                rotateLogFiles(logDirectory)
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    @Synchronized
    private fun rotateLogFiles(directory: Path) {
        fileLock.lock()
        try {
            if (!Files.exists(directory)) {
                Files.createDirectories(directory)
            }

            // 获取并按时间戳排序日志文件
            val logFiles = Files.list(directory).use { stream ->
                stream.filter { path ->
                    val name = path.fileName.toString()
                    name.startsWith("app_runtime_log_") && name.endsWith(".txt")
                }.map { path ->
                    val name = path.fileName.toString()
                    val timestampStr = name.removePrefix("app_runtime_log_").removeSuffix(".txt")
                    timestampStr.toLongOrNull()?.let { timestamp -> path to timestamp }
                }.filter { it != null }.map { it }.collect(Collectors.toList())
            }.sortedBy { it.second }.map { it.first }

            // 如果日志数量达到上限，删除最早的
            if (logFiles.size >= MAX_LOG_FILES) {
                Files.deleteIfExists(logFiles.first())
            }

            // 生成新日志文件名
            val newTimestamp = System.currentTimeMillis()
            val newLogFile = directory.resolve("app_runtime_log_$newTimestamp.txt")

            // 重命名当前日志文件
            Files.move(currentLogFile, newLogFile)

            // 创建新的空日志文件
            Files.createFile(currentLogFile)
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            fileLock.unlock()
        }
    }

    @Synchronized
    fun writeLog(log: String) {
        if (::currentLogFile.isInitialized) {
            fileLock.lock()
            try {
                Log.d(kTag, log)
                val time = System.currentTimeMillis().timestampToCompleteDate()
                val str = "$time ${log}${System.lineSeparator()}"
                Files.write(currentLogFile, str.toByteArray(), StandardOpenOption.APPEND)
            } catch (e: IOException) {
                e.printStackTrace()
            } finally {
                fileLock.unlock()
            }
        } else {
            throw IllegalStateException("Log file not initialized. Call initLogFile first.")
        }
    }

    /**
     * 读取全部日志文件（当前日志 + 轮转历史），按修改时间倒序
     * */
    @Synchronized
    fun getLogFiles(): List<Path> {
        if (!::logDirectory.isInitialized) {
            throw IllegalStateException("Log file not initialized. Call initLogFile first.")
        }
        return Files.list(logDirectory).use { stream ->
            stream.filter { path ->
                val name = path.fileName.toString()
                Files.isRegularFile(path) && name.startsWith(LOG_FILE_PREFIX)
                        && name.endsWith(LOG_FILE_SUFFIX) && Files.size(path) > 0
            }.collect(Collectors.toList())
        }.sortedByDescending { Files.getLastModifiedTime(it).toMillis() }
    }

    /**
     * 把全部日志文件打包成一个 zip，用于对外分享
     *
     * @return zip 文件；没有任何日志时返回 null
     * */
    @Synchronized
    fun exportLogFiles(context: Context, zipName: String = "app_runtime_logs.zip"): File? {
        if (!::logDirectory.isInitialized) {
            throw IllegalStateException("Log file not initialized. Call initLogFile first.")
        }
        val logFiles = getLogFiles()
        if (logFiles.isEmpty()) {
            Log.w(kTag, "没有可导出的日志文件")
            return null
        }
        val exportDir = File(context.cacheDir, "log_export").apply {
            if (!exists()) {
                mkdirs()
            }
            // 只保留最新一份导出包
            listFiles()?.forEach { it.deleteRecursively() }
        }
        val zipFile = File(exportDir, zipName)
        fileLock.lock()
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zip ->
                logFiles.forEach { path ->
                    zip.putNextEntry(ZipEntry(path.fileName.toString()))
                    Files.copy(path, zip)
                    zip.closeEntry()
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
            return null
        } finally {
            fileLock.unlock()
        }
        Log.d(kTag, "日志已导出：${zipFile.absolutePath}")
        return zipFile
    }
}