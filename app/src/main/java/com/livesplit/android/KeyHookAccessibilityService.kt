package com.livesplit.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class KeyHookAccessibilityService : AccessibilityService() {

    private val TAG = "LiveSplitKeyHook"

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        serviceInfo = info
        Log.d(TAG, "KeyHookAccessibilityService Connected & Requesting Key Filtering")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val keyCode = event.keyCode

            // 1. DYNAMIC BINDING LOGIC
            if (TimerState.listeningForKeybind.value != 0) {
                Log.d(TAG, "Binding KeyCode: $keyCode")
                TimerState.assignKeybind(keyCode)
                return true // Consume the event so the OS/Emulator doesn't see it
            }

            // 2. TRIGGER LOGIC
            when (keyCode) {
                TimerState.splitKey.value -> { TimerState.startOrSplit(); return true }
                TimerState.undoKey.value -> { TimerState.undoSplit(); return true }
                TimerState.skipKey.value -> { TimerState.skipSegment(); return true }
            }
        }
        return false
    }
}