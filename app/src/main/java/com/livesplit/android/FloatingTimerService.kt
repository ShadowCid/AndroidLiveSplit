package com.livesplit.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import java.io.File

class FloatingTimerService : LifecycleService(), ViewModelStoreOwner, SavedStateRegistryOwner {

    companion object {
        const val ACTION_STOP_SERVICE = "com.livesplit.android.ACTION_STOP_SERVICE"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private var wrapperView: FrameLayout? = null
    private var isViewAdded = false

    private val myViewModelStore = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = myViewModelStore

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 100
        }

        // We wrap ComposeView in a FrameLayout to override dispatchTouchEvent
        wrapperView = object : FrameLayout(this@FloatingTimerService) {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isDragging = false

            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()

                        // If moved more than 10 pixels, start dragging and cancel Compose tap
                        if (!isDragging && (Math.abs(dx) > 10 || Math.abs(dy) > 10)) {
                            isDragging = true
                            val cancelEvent = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                            super.dispatchTouchEvent(cancelEvent)
                            cancelEvent.recycle()
                        }

                        if (isDragging) {
                            params.x = initialX + dx
                            params.y = initialY + dy
                            windowManager.updateViewLayout(this, params)
                            return true // Consume dragging event
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (isDragging) {
                            isDragging = false
                            return true // Consume UP event if dragged
                        }
                    }
                }
                // Pass untouched events (like pure taps) to Compose
                return super.dispatchTouchEvent(event)
            }
        }.apply {
            setViewTreeLifecycleOwner(this@FloatingTimerService)
            setViewTreeViewModelStoreOwner(this@FloatingTimerService)
            setViewTreeSavedStateRegistryOwner(this@FloatingTimerService)

            val composeView = ComposeView(this@FloatingTimerService).apply {
                setContent {
                    MaterialTheme {
                        val elapsedTimeMs by TimerState.elapsedTimeMs.collectAsState()
                        val segments by TimerState.segments.collectAsState()
                        val gameName by TimerState.gameName.collectAsState()
                        val categoryName by TimerState.categoryName.collectAsState()
                        val timerStatus by TimerState.status.collectAsState()
                        val currentSegmentIndex by TimerState.currentSegmentIndex.collectAsState()

                        // New Visual States!
                        val bgOpacity by TimerState.overlayOpacity.collectAsState()
                        val timerFontSize by TimerState.timerFontSize.collectAsState()
                        val useDigital by TimerState.useDigitalFont.collectAsState()

                        var isCompact by remember { mutableStateOf(false) }

                        val currentFontFamily = if (useDigital) FontFamily.Monospace else FontFamily.Default

                        // Visually reorder the segments to replicate LiveSplit's grouped layout
                        // where the Main Split acts as a Header ABOVE its subsplits.
                        val flatVisualItems = remember(segments, currentSegmentIndex) {
                            val list = mutableListOf<Pair<Int, Segment>>() // Pair of (Original Index, Segment)
                            val currentGroupSubs = mutableListOf<Pair<Int, Segment>>()

                            for ((index, seg) in segments.withIndex()) {
                                if (seg.isSubsplit) {
                                    currentGroupSubs.add(index to seg)
                                } else {
                                    // 1. Add the Main Split FIRST so it acts as a visual Header
                                    list.add(index to seg)

                                    // 2. Check if this group is currently active
                                    val isActiveGroup = currentSegmentIndex == index || currentGroupSubs.any { it.first == currentSegmentIndex }
                                    val isFirstGroup = index == segments.indexOfFirst { !it.isSubsplit }
                                    val showSubs = isActiveGroup || (currentSegmentIndex < 0 && isFirstGroup)

                                    // 3. If active, attach the subsplits underneath the Header
                                    if (showSubs) {
                                        list.addAll(currentGroupSubs)
                                    }
                                    currentGroupSubs.clear()
                                }
                            }

                            // Edge case: If the file ends with subsplits without a closing main split
                            if (currentGroupSubs.isNotEmpty()) {
                                val isActiveGroup = currentGroupSubs.any { it.first == currentSegmentIndex }
                                val showSubs = isActiveGroup || currentSegmentIndex < 0
                                if (showSubs) {
                                    list.addAll(currentGroupSubs)
                                }
                            }

                            list
                        }

                        Column(
                            modifier = Modifier
                                .widthIn(min = if (isCompact) 150.dp else 280.dp) // Changed from strict .width() so it can expand
                                .background(Color.Black.copy(alpha = bgOpacity)) // Apply dynamic opacity here
                                .padding(8.dp)
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onDoubleTap = { isCompact = !isCompact },
                                        onTap = { TimerState.startOrSplit() }
                                    )
                                }
                        ) {
                            if (!isCompact) {
                                Text(text = "$gameName - $categoryName", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)

                                LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                                    // Iterate over our visually re-ordered list, but use the original Index for exact time math
                                    items(flatVisualItems, key = { it.first }) { (originalIndex, seg) ->
                                        val splitTime = seg.splitTimeMs
                                        val pbTime = seg.personalBestTimeMs

                                        val prevSplitTime = if (originalIndex > 0) segments[originalIndex - 1].splitTimeMs ?: 0L else 0L
                                        val prevPbTime = if (originalIndex > 0) segments[originalIndex - 1].personalBestTimeMs else 0L

                                        val currentSegTime = if (splitTime != null) splitTime - prevSplitTime else null
                                        val pbSegTime = if (pbTime > 0L) pbTime - prevPbTime else 0L

                                        val diff = if (splitTime != null && pbTime > 0L) TimerState.formatTimeDiff(splitTime, pbTime) else ""
                                        val formattedTime = if (splitTime != null) TimerState.formatTime(splitTime) else "--"

                                        var color = Color.Gray
                                        if (splitTime != null && pbTime > 0L) {
                                            val isGold = currentSegTime != null &&
                                                    (seg.bestSegmentTimeMs == 0L || currentSegTime < seg.bestSegmentTimeMs) &&
                                                    (pbSegTime == 0L || currentSegTime < pbSegTime)

                                            if (isGold) {
                                                color = Color(0xFFFFD700) // Gold
                                            } else if (splitTime <= pbTime) {
                                                color = Color(0xFF00FF00) // Green
                                            } else {
                                                color = Color(0xFFFF4444) // Red
                                            }
                                        }

                                        val isHeader = !seg.isSubsplit
                                        val prefix = if (!isHeader) "└ " else ""
                                        val nameColor = if (!isHeader) Color.LightGray else Color.White
                                        val fontSize = if (!isHeader) 12.sp else 14.sp
                                        val fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal
                                        val paddingStart = if (!isHeader) 16.dp else 0.dp

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 2.dp)
                                                .padding(start = paddingStart),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(text = "$prefix${seg.name}", color = nameColor, fontSize = fontSize, fontWeight = fontWeight, modifier = Modifier.weight(1f))
                                            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                                                Text(text = formattedTime, color = Color.White, fontFamily = currentFontFamily, fontSize = 14.sp)
                                                if (splitTime != null && pbTime > 0L) {
                                                    Text(text = diff, color = color, fontFamily = currentFontFamily, fontSize = 10.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                Text(
                                    text = "Splits: ${TimerState.getSplitsRemaining()} left",
                                    color = Color.LightGray,
                                    fontSize = 10.sp
                                )
                            }

                            Text(
                                text = TimerState.formatTime(elapsedTimeMs),
                                color = if (timerStatus == TimerStatus.RUNNING) Color.Green else Color.White,
                                fontSize = if (isCompact) (timerFontSize * 0.6f).sp else timerFontSize.sp, // Slightly more compact scaling
                                fontWeight = FontWeight.Black,
                                fontFamily = currentFontFamily, // Apply dynamic font here
                                maxLines = 1, // Prevent stacking
                                softWrap = false // Prevent wrapping to new lines
                            )

                            if (!isCompact) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    val btnMod = Modifier.weight(1f).padding(horizontal = 2.dp).height(36.dp)
                                    val btnPadding = PaddingValues(0.dp) // Removes the default padding that squishes the text
                                    val btnColors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                                    val btnShape = RoundedCornerShape(4.dp)

                                    Button(onClick = { TimerState.startOrSplit() }, modifier = btnMod, shape = btnShape, contentPadding = btnPadding, colors = btnColors) {
                                        Text("Start/Split", fontSize = 11.sp, maxLines = 1)
                                    }
                                    Button(onClick = { TimerState.undoSplit() }, modifier = btnMod, shape = btnShape, contentPadding = btnPadding, colors = btnColors) {
                                        Text("Undo", fontSize = 11.sp, maxLines = 1)
                                    }
                                    Button(onClick = { TimerState.skipSegment() }, modifier = btnMod, shape = btnShape, contentPadding = btnPadding, colors = btnColors) {
                                        Text("Skip", fontSize = 11.sp, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            addView(composeView)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // Handle stop before doing anything visual
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Only add the view if we are actually starting and haven't already added it
        if (!isViewAdded && wrapperView != null) {
            try {
                windowManager.addView(wrapperView, params)
                isViewAdded = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        myViewModelStore.clear()

        // Only attempt to autosave if the overlay was actually rendering data
        if (isViewAdded && TimerState.segments.value.isNotEmpty()) {

            // Force commit any completed run data to PB/BestSegments before we save
            if (TimerState.status.value == TimerStatus.FINISHED) {
                TimerState.commitRunIfPB()
            }

            // 1. Auto-save the current splits to a temporary internal file
            try {
                val xmlData = LssExporter.export(
                    TimerState.gameName.value,
                    TimerState.categoryName.value,
                    TimerState.attemptCount.value,
                    TimerState.segments.value
                )
                val file = File(filesDir, "autosave.lss")
                file.writeText(xmlData)
                Toast.makeText(this, "Run temporarily auto-saved. Open the main app to permanently save your .lss file.", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 2. Stop the timer engine so it doesn't continue ticking in the background
        TimerState.resetRun()

        // 3. Remove the View
        try {
            if (isViewAdded) {
                wrapperView?.let { windowManager.removeView(it) }
                isViewAdded = false
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = null
}