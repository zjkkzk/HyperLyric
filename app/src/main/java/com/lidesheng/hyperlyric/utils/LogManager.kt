package com.lidesheng.hyperlyric.utils

import android.content.Context
import com.lidesheng.hyperlyric.R
import com.lidesheng.hyperlyric.ui.page.LogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.regex.Pattern

object LogManager {
    suspend fun readXposedLogs(context: Context): List<LogEntry> = withContext(Dispatchers.IO) {
        val entries = mutableListOf<LogEntry>()
        try {
            val findProcess = Runtime.getRuntime().exec(
                arrayOf("su", "-c",
                    "ls -d /data/adb/lspd/log /data/adb/lspd/log.old 2>/dev/null || echo '__NONE__'"
                )
            )
            val foundDirs = BufferedReader(InputStreamReader(findProcess.inputStream))
                .readLines().filter { it.isNotBlank() && it != "__NONE__" }
            findProcess.waitFor()

            if (foundDirs.isEmpty()) {
                val msg = context.getString(R.string.lsposed_not_found)
                entries.add(LogEntry("NOW", "W", context.getString(R.string.tag_logger), msg, rawLog = msg))
                return@withContext entries
            }

            val dirsArg = foundDirs.joinToString(" ")
            val listProcess = Runtime.getRuntime().exec(
                arrayOf("su", "-c", "find $dirsArg -name '*.log' ! -name 'kmsg*' -type f 2>/dev/null")
            )
            val logFiles = BufferedReader(InputStreamReader(listProcess.inputStream))
                .readLines().filter { it.isNotBlank() }
            listProcess.waitFor()

            if (logFiles.isEmpty()) {
                val msg = context.getString(R.string.format_log_files_not_found, dirsArg)
                entries.add(LogEntry("NOW", "W", context.getString(R.string.tag_logger), msg, rawLog = msg))
                return@withContext entries
            }

            val catCmd = logFiles.joinToString(" ") { "'$it'" }
            val process = Runtime.getRuntime().exec(
                arrayOf("su", "-c", "cat $catCmd 2>/dev/null")
            )

            val timeRegex = Pattern.compile("^(?:\\[\\s*)?(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}|\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d{3}|\\d+\\.\\d{6})")
            val levelRegex = Pattern.compile("\\s+([VDIWEC])/")
            val lsposedRegex = Pattern.compile("\\(([^)]+)\\)\\[([^,\\]]+)")
            val processRegex = Pattern.compile("\\(([^)]+)\\)")

            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                val currentBlock = java.lang.StringBuilder()

                fun processCurrentBlock() {
                    val blockStr = currentBlock.toString()
                    val isHyperLyric = blockStr.contains("hyperlyric", ignoreCase = true) || blockStr.contains("HyperLyric")
                    val isSystemUi = blockStr.contains("systemui", ignoreCase = true) || blockStr.contains("SystemUI")
                    val isLyricon = blockStr.contains("io.github.proify.lyricon", ignoreCase = true)
                    val isSystemUiCrash = isSystemUi && 
                                          (blockStr.contains("crash", ignoreCase = true) || blockStr.contains("fatal exception", ignoreCase = true))

                    if (isHyperLyric || isSystemUi || isLyricon) {
                        val firstLine = blockStr.lineSequence().firstOrNull() ?: ""

                        val timeMatcher = timeRegex.matcher(firstLine)
                        val rawTime = if (timeMatcher.find()) timeMatcher.group(1) ?: context.getString(R.string.unknown_time) else context.getString(R.string.unknown_time)
                        val time = if (rawTime.length >= 19 && rawTime != context.getString(R.string.unknown_time)) rawTime.substring(5).replace('T', ' ') else rawTime

                        val levelMatcher = levelRegex.matcher(firstLine)
                        val parsedLevel = if (levelMatcher.find()) levelMatcher.group(1) ?: "I" else "I"
                        val level = when {
                            isSystemUiCrash -> "C"
                            blockStr.contains(" E/", ignoreCase = true) || blockStr.contains("[E]", ignoreCase = true) || blockStr.contains("error", ignoreCase = true) || blockStr.contains("fail", ignoreCase = true) || blockStr.contains("exception", ignoreCase = true) -> "E"
                            blockStr.contains(" W/", ignoreCase = true) || blockStr.contains("[W]", ignoreCase = true) || blockStr.contains("warn", ignoreCase = true) -> "W"
                            blockStr.contains(" D/", ignoreCase = true) || blockStr.contains("[D]", ignoreCase = true) || blockStr.contains("debug", ignoreCase = true) -> "D"
                            else -> parsedLevel
                        }

                        if (level == "V") return

                        val tagStart = firstLine.indexOf("[HyperLyric]")
                        val headerMsg = if (tagStart != -1) {
                            firstLine.substring(tagStart + "[HyperLyric]".length).trim().ifEmpty { firstLine }
                        } else {
                            val lastBracket = firstLine.lastIndexOf(']')
                            if (lastBracket != -1 && lastBracket < firstLine.length - 1) {
                                firstLine.substring(lastBracket + 1).trim()
                            } else firstLine
                        }

                        val remainingLines = if (blockStr.contains('\n')) blockStr.substringAfter('\n') else ""
                        val finalMsg = if (remainingLines.isNotBlank()) {
                            if (headerMsg.isNotEmpty() && headerMsg != firstLine) "$headerMsg\n$remainingLines" else "$headerMsg\n$remainingLines"
                        } else {
                            headerMsg
                        }

                        val lsposedMatcher = lsposedRegex.matcher(firstLine)
                        val source = if (lsposedMatcher.find()) {
                            lsposedMatcher.group(2) ?: "com.lidesheng.hyperlyric"
                        } else {
                            val processMatcher = processRegex.matcher(firstLine)
                            if (processMatcher.find()) {
                                processMatcher.group(1) ?: "com.lidesheng.hyperlyric"
                            } else if (isSystemUi) {
                                "com.android.systemui"
                            } else {
                                "com.lidesheng.hyperlyric"
                            }
                        }
                        val tag = if (isSystemUi && !isHyperLyric && !isLyricon) context.getString(R.string.tag_logger) else context.getString(R.string.tag_lsposed)
                        entries.add(LogEntry(time, level, tag, finalMsg.trim(), source = source, rawLog = blockStr))
                    }
                }

                reader.lineSequence().forEach { line ->
                    if (timeRegex.matcher(line).find()) {
                        if (currentBlock.isNotEmpty()) {
                            processCurrentBlock()
                            currentBlock.clear()
                        }
                    }
                    if (currentBlock.isNotEmpty()) currentBlock.append("\n")
                    currentBlock.append(line)
                }
                if (currentBlock.isNotEmpty()) {
                    processCurrentBlock()
                }
            }
            process.waitFor()

            if (entries.isEmpty()) {
                val msg = context.getString(R.string.format_logs_scanned_no_match, logFiles.size, dirsArg)
                entries.add(LogEntry("NOW", "I", context.getString(R.string.tag_logger), msg, rawLog = msg))
            }
        } catch (e: Exception) {
            val msg = if (e.message?.contains("Permission denied") == true ||
                          e.message?.contains("su:") == true ||
                          e.message?.contains("not found") == true) {
                context.getString(R.string.no_root_permission)
            } else {
                context.getString(R.string.format_log_read_failed, e.message)
            }
            entries.add(LogEntry("NOW", "E", context.getString(R.string.tag_logger), msg, rawLog = msg))
        }
        entries.sortedByDescending { it.timestamp }
    }

    suspend fun collectAllLogs(context: Context): List<LogEntry> {
        return readXposedLogs(context)
    }
}
