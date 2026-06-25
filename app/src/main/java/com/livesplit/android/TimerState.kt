package com.livesplit.android

import android.view.KeyEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class TimerStatus { NOT_STARTED, RUNNING, PAUSED, FINISHED }

data class Segment(
    val name: String,
    val personalBestTimeMs: Long,
    val bestSegmentTimeMs: Long = 0L,
    val splitTimeMs: Long? = null,
    val isCurrent: Boolean = false,
    val isCompleted: Boolean = false,
    val isSubsplit: Boolean = false,
    val id: String = java.util.UUID.randomUUID().toString()
)

object TimerState {
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var tickJob: Job? = null

    val gameName = MutableStateFlow("New Game")
    val categoryName = MutableStateFlow("Any%")
    val attemptCount = MutableStateFlow(0)
    val status = MutableStateFlow(TimerStatus.NOT_STARTED)
    val segments = MutableStateFlow<List<Segment>>(emptyList())
    val currentSegmentIndex = MutableStateFlow(-1)
    val elapsedTimeMs = MutableStateFlow(0L)

    // --- VISUAL CUSTOMIZATION STATE ---
    val overlayOpacity = MutableStateFlow(0.85f) // 0.0 to 1.0 (Alpha)
    val timerFontSize = MutableStateFlow(32) // Standard text size
    val useDigitalFont = MutableStateFlow(true) // Monospace vs Default

    private var startTimeMs = 0L
    private var accumulatedTimeMs = 0L

    val splitKey = MutableStateFlow(KeyEvent.KEYCODE_VOLUME_UP)
    val undoKey = MutableStateFlow(KeyEvent.KEYCODE_VOLUME_DOWN)
    val skipKey = MutableStateFlow(-1)
    val listeningForKeybind = MutableStateFlow(0)

    fun listenForKeybind(actionId: Int) { listeningForKeybind.value = actionId }

    fun assignKeybind(keyCode: Int) {
        when (listeningForKeybind.value) {
            1 -> splitKey.value = keyCode
            2 -> undoKey.value = keyCode
            3 -> skipKey.value = keyCode
        }
        listeningForKeybind.value = 0
    }

    fun startOrSplit() {
        synchronized(this) {
            when (status.value) {
                TimerStatus.NOT_STARTED -> {
                    startTimeMs = System.currentTimeMillis()
                    accumulatedTimeMs = 0L
                    status.value = TimerStatus.RUNNING
                    currentSegmentIndex.value = 0
                    updateSegmentStatus(0)
                    startTicking()
                }
                TimerStatus.RUNNING -> {
                    val index = currentSegmentIndex.value
                    val currentList = segments.value.toMutableList()
                    val elapsed = accumulatedTimeMs + (System.currentTimeMillis() - startTimeMs)

                    if (currentList.isNotEmpty() && index in currentList.indices) {
                        val currentSeg = currentList[index]
                        currentList[index] = currentSeg.copy(
                            splitTimeMs = elapsed,
                            isCompleted = true,
                            isCurrent = false
                        )
                        segments.value = currentList

                        if (index + 1 < currentList.size) {
                            currentSegmentIndex.value = index + 1
                            updateSegmentStatus(index + 1)
                        } else {
                            status.value = TimerStatus.FINISHED
                            stopTicking()
                        }
                    } else {
                        status.value = TimerStatus.FINISHED
                        stopTicking()
                    }
                }
                TimerStatus.FINISHED -> {
                    resetRun()
                }
                TimerStatus.PAUSED -> {
                    startTimeMs = System.currentTimeMillis()
                    status.value = TimerStatus.RUNNING
                    startTicking()
                }
            }
        }
    }

    fun undoSplit() {
        synchronized(this) {
            if (status.value == TimerStatus.NOT_STARTED) return

            val index = currentSegmentIndex.value
            val targetIndex = if (status.value == TimerStatus.FINISHED) index else index - 1

            if (targetIndex in segments.value.indices) {
                val list = segments.value.toMutableList()
                list[targetIndex] = list[targetIndex].copy(splitTimeMs = null, isCompleted = false)
                segments.value = list
                currentSegmentIndex.value = targetIndex
                updateSegmentStatus(targetIndex)

                if (status.value == TimerStatus.FINISHED) {
                    status.value = TimerStatus.RUNNING
                    startTimeMs = System.currentTimeMillis()
                    accumulatedTimeMs = elapsedTimeMs.value
                    startTicking()
                }
            } else if (targetIndex < 0) {
                resetRun()
            }
        }
    }

    fun skipSegment() {
        synchronized(this) {
            val index = currentSegmentIndex.value
            if (status.value == TimerStatus.RUNNING && index in segments.value.indices) {
                if (index + 1 < segments.value.size) {
                    currentSegmentIndex.value = index + 1
                    updateSegmentStatus(index + 1)
                } else {
                    val currentList = segments.value.toMutableList()
                    currentList[index] = currentList[index].copy(isCompleted = true, isCurrent = false)
                    segments.value = currentList
                    status.value = TimerStatus.FINISHED
                    stopTicking()
                }
            }
        }
    }

    fun resetRun() {
        stopTicking()

        if (status.value == TimerStatus.FINISHED) {
            commitRunIfPB()
        }

        status.value = TimerStatus.NOT_STARTED
        currentSegmentIndex.value = -1
        elapsedTimeMs.value = 0L
        accumulatedTimeMs = 0L
        startTimeMs = 0L
        segments.value = segments.value.map { it.copy(splitTimeMs = null, isCompleted = false, isCurrent = false) }
    }

    fun commitRunIfPB() {
        val currentSegs = segments.value
        if (currentSegs.isEmpty()) return

        attemptCount.value += 1

        val lastSeg = currentSegs.last()
        val runTime = lastSeg.splitTimeMs ?: return

        val isNewPB = lastSeg.personalBestTimeMs == 0L || runTime < lastSeg.personalBestTimeMs

        segments.value = currentSegs.mapIndexed { index, seg ->
            val prevSplit = if (index > 0) currentSegs[index - 1].splitTimeMs ?: 0L else 0L
            val currentSegTime = (seg.splitTimeMs ?: 0L) - prevSplit

            val newBestSeg = if (seg.bestSegmentTimeMs == 0L || (currentSegTime > 0 && currentSegTime < seg.bestSegmentTimeMs)) {
                currentSegTime
            } else {
                seg.bestSegmentTimeMs
            }

            val newPb = if (isNewPB) (seg.splitTimeMs ?: seg.personalBestTimeMs) else seg.personalBestTimeMs

            seg.copy(
                bestSegmentTimeMs = newBestSeg,
                personalBestTimeMs = newPb
            )
        }
    }

    fun createNewRun(game: String, cat: String, segmentNames: List<String>) {
        gameName.value = game
        categoryName.value = cat
        attemptCount.value = 0
        segments.value = segmentNames.map { Segment(name = it, personalBestTimeMs = 0L) }
        resetRun()
    }

    fun loadRunData(run: ParsedRun) {
        gameName.value = run.gameName
        categoryName.value = run.categoryName
        attemptCount.value = run.attemptCount
        segments.value = run.segments
        resetRun()
    }

    fun loadSegments(names: List<String>, pbTimes: List<Long>) {
        segments.value = names.mapIndexed { index, name ->
            Segment(name = name, personalBestTimeMs = pbTimes.getOrElse(index) { 0L })
        }
    }

    fun addSegment(name: String) {
        segments.value = segments.value + Segment(name, 0L)
    }

    fun removeSegment(index: Int) {
        segments.value = segments.value.filterIndexed { i, _ -> i != index }
    }

    fun updateSegmentSubsplit(index: Int, isSubsplit: Boolean) {
        val list = segments.value.toMutableList()
        if (index in list.indices) {
            list[index] = list[index].copy(isSubsplit = isSubsplit)
            segments.value = list
        }
    }

    fun updateSegmentPB(index: Int, timeMs: Long) {
        val list = segments.value.toMutableList()
        if (index in list.indices) {
            list[index] = list[index].copy(personalBestTimeMs = timeMs)
            segments.value = list
        }
    }

    fun updateSegmentBest(index: Int, timeMs: Long) {
        val list = segments.value.toMutableList()
        if (index in list.indices) {
            list[index] = list[index].copy(bestSegmentTimeMs = timeMs)
            segments.value = list
        }
    }

    fun updateSegmentName(index: Int, name: String) {
        val list = segments.value.toMutableList()
        if (index in list.indices) {
            list[index] = list[index].copy(name = name)
            segments.value = list
        }
    }

    fun clearRunData() {
        gameName.value = "New Game"
        categoryName.value = "Any%"
        attemptCount.value = 0
        segments.value = emptyList()
        resetRun()
    }

    private fun updateSegmentStatus(activeIndex: Int) {
        segments.value = segments.value.mapIndexed { i, s -> s.copy(isCurrent = (i == activeIndex), isCompleted = (i < activeIndex)) }
    }

    private fun startTicking() {
        tickJob?.cancel()
        tickJob = coroutineScope.launch {
            while (isActive && status.value == TimerStatus.RUNNING) {
                elapsedTimeMs.value = accumulatedTimeMs + (System.currentTimeMillis() - startTimeMs)
                delay(16)
            }
        }
    }

    private fun stopTicking() { tickJob?.cancel() }

    fun formatTime(timeMs: Long, includeMillis: Boolean = true): String {
        val totalSecs = timeMs / 1000
        val mins = (totalSecs % 3600) / 60
        val secs = totalSecs % 60
        val hundredths = (timeMs % 1000) / 10
        return String.format("%d:%02d.%02d", mins, secs, hundredths)
    }

    fun formatTimeDiff(currentMs: Long, comparisonMs: Long): String {
        val diff = currentMs - comparisonMs
        val absoluteDiff = Math.abs(diff)
        val sign = if (diff >= 0) "+" else "-"
        val totalSecs = absoluteDiff / 1000
        val secs = totalSecs % 60
        val hundredths = (absoluteDiff % 1000) / 10
        return "$sign$secs.${String.format("%02d", hundredths)}"
    }

    fun parseTimeStringToMs(timeStr: String): Long {
        if (timeStr.isBlank()) return 0L
        return try {
            var cleanStr = timeStr.trim()
            val isNegative = cleanStr.startsWith("-")
            if (isNegative) cleanStr = cleanStr.substring(1)

            val parts = cleanStr.split(":")
            if (parts.isEmpty()) return 0L

            var hours = 0L
            var minutes = 0L
            var seconds = 0L
            var milliseconds = 0L

            when (parts.size) {
                1 -> {
                    val secParts = parts[0].split(".")
                    seconds = secParts[0].toLong()
                    if (secParts.size > 1) milliseconds = secParts[1].padEnd(3, '0').substring(0, 3).toLong()
                }
                2 -> {
                    minutes = parts[0].toLong()
                    val secParts = parts[1].split(".")
                    seconds = secParts[0].toLong()
                    if (secParts.size > 1) milliseconds = secParts[1].padEnd(3, '0').substring(0, 3).toLong()
                }
                3 -> {
                    hours = parts[0].toLong()
                    minutes = parts[1].toLong()
                    val secParts = parts[2].split(".")
                    seconds = secParts[0].toLong()
                    if (secParts.size > 1) milliseconds = secParts[1].padEnd(3, '0').substring(0, 3).toLong()
                }
            }

            val totalMs = (hours * 3600000) + (minutes * 60000) + (seconds * 1000) + milliseconds
            if (isNegative) -totalMs else totalMs
        } catch (e: Exception) {
            0L
        }
    }

    fun getSplitsRemaining(): Int {
        if (status.value == TimerStatus.FINISHED) return 0
        if (status.value == TimerStatus.NOT_STARTED) return segments.value.size
        return segments.value.size - currentSegmentIndex.value
    }
}