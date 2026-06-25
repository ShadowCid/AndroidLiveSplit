package com.livesplit.android

import java.util.Locale

object LssExporter {

    fun export(game: String, category: String, attemptCount: Int, segments: List<Segment>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<Run version=\"1.7.0\">\n")
        sb.append("  <GameName>${game.replace("&", "&amp;")}</GameName>\n")
        sb.append("  <CategoryName>${category.replace("&", "&amp;")}</CategoryName>\n")
        sb.append("  <AttemptCount>${attemptCount}</AttemptCount>\n")
        sb.append("  <Segments>\n")

        for (seg in segments) {
            val exportName = if (seg.isSubsplit) "- ${seg.name}" else seg.name

            sb.append("    <Segment>\n")
            sb.append("      <Name>${exportName.replace("&", "&amp;")}</Name>\n")
            sb.append("      <SplitTimes>\n")
            sb.append("        <SplitTime name=\"Personal Best\">\n")
            sb.append("          <RealTime>${formatTime(seg.personalBestTimeMs)}</RealTime>\n")
            sb.append("        </SplitTime>\n")
            sb.append("      </SplitTimes>\n")
            sb.append("    </Segment>\n")
        }

        sb.append("  </Segments>\n")
        sb.append("</Run>")
        return sb.toString()
    }

    private fun formatTime(ms: Long): String {
        val totalSecs = ms / 1000
        val hours = totalSecs / 3600
        val mins = (totalSecs % 3600) / 60
        val secs = totalSecs % 60
        val millis = ms % 1000
        // LiveSplit expects HH:MM:SS.fffffff
        return String.format(Locale.US, "%02d:%02d:%02d.%06d0", hours, mins, secs, millis * 1000)
    }
}