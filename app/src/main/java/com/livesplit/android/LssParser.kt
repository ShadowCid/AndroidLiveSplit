package com.livesplit.android

import android.util.Log
import org.w3c.dom.Element
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Data class to hold the results of a parsed .lss file
 */
data class ParsedRun(
    val gameName: String,
    val categoryName: String,
    val attemptCount: Int,
    val segments: List<Segment>
)

object LssParser {
    private const val TAG = "LssParser"

    /**
     * Parses a LiveSplit .lss (XML) file from an InputStream.
     */
    fun parse(inputStream: InputStream): ParsedRun? {
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(inputStream)
            doc.documentElement.normalize()

            // 1. Extract Run Metadata
            val gameName = getElementText(doc.documentElement, "GameName") ?: "Unknown Game"
            val categoryName = getElementText(doc.documentElement, "CategoryName") ?: "Unknown Category"

            // Extract AttemptCount
            val attemptCountStr = getElementText(doc.documentElement, "AttemptCount") ?: "0"
            val attemptCount = attemptCountStr.toIntOrNull() ?: 0

            // 2. Extract Segments
            val segments = mutableListOf<Segment>()
            val segmentsNode = doc.getElementsByTagName("Segments").item(0) as? Element

            if (segmentsNode != null) {
                val segmentNodes = segmentsNode.getElementsByTagName("Segment")
                for (i in 0 until segmentNodes.length) {
                    val segmentElement = segmentNodes.item(i) as Element
                    val rawName = getElementText(segmentElement, "Name") ?: "Segment ${i + 1}"

                    // LiveSplit standard: subsplits are prefixed with "-"
                    val isSubsplit = rawName.trimStart().startsWith("-")
                    val name = if (isSubsplit) rawName.trimStart().substring(1).trim() else rawName

                    // Dig into SplitTimes -> SplitTime name="Personal Best" -> RealTime
                    var pbTimeMs = 0L
                    val splitTimesNode = segmentElement.getElementsByTagName("SplitTimes").item(0) as? Element
                    if (splitTimesNode != null) {
                        val splitTimeNodes = splitTimesNode.getElementsByTagName("SplitTime")
                        for (j in 0 until splitTimeNodes.length) {
                            val splitTimeElement = splitTimeNodes.item(j) as Element
                            if (splitTimeElement.getAttribute("name") == "Personal Best") {
                                val realTimeStr = getElementText(splitTimeElement, "RealTime")
                                pbTimeMs = parseTimeString(realTimeStr)
                                break
                            }
                        }
                    }

                    segments.add(
                        Segment(
                            name = name,
                            personalBestTimeMs = pbTimeMs,
                            splitTimeMs = null,
                            isCurrent = false,
                            isCompleted = false,
                            isSubsplit = isSubsplit
                        )
                    )
                }
            }

            // Return the ParsedRun object
            ParsedRun(gameName, categoryName, attemptCount, segments)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse LSS file: ${e.message}")
            null
        }
    }

    /**
     * Helper to extract text from a specific XML tag.
     */
    private fun getElementText(parent: Element, tagName: String): String? {
        val nodeList = parent.getElementsByTagName(tagName)
        if (nodeList.length > 0) {
            return nodeList.item(0).textContent
        }
        return null
    }

    /**
     * Converts a LiveSplit time string (e.g. "00:04:15.1230000") into milliseconds.
     */
    private fun parseTimeString(timeStr: String?): Long {
        if (timeStr.isNullOrBlank()) return 0L

        try {
            // LiveSplit format is typically [-]HH:MM:SS.fffffff
            var cleanStr = timeStr.trim()
            val isNegative = cleanStr.startsWith("-")
            if (isNegative) cleanStr = cleanStr.substring(1)

            val parts = cleanStr.split(":")
            if (parts.isEmpty()) return 0L

            var hours = 0L
            var minutes = 0L
            var seconds = 0L
            var milliseconds = 0L

            if (parts.size == 3) {
                hours = parts[0].toLong()
                minutes = parts[1].toLong()
                val secParts = parts[2].split(".")
                seconds = secParts[0].toLong()
                if (secParts.size > 1) {
                    // Truncate to 3 digits for milliseconds (LiveSplit goes to 7)
                    val msStr = secParts[1].padEnd(3, '0').substring(0, 3)
                    milliseconds = msStr.toLong()
                }
            }

            val totalMs = (hours * 3600000) + (minutes * 60000) + (seconds * 1000) + milliseconds
            return if (isNegative) -totalMs else totalMs

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse time string '$timeStr': ${e.message}")
            return 0L
        }
    }
}