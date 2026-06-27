package com.livesplit.android

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

class KeyHookAccessibilityService : AccessibilityService() {

    private val TAG = "LiveSplitKeyHook"
    private var buttonCallback: AccessibilityButtonController.AccessibilityButtonCallback? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = serviceInfo ?: AccessibilityServiceInfo()
        // Dynamically request the button flag here in code
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON
        serviceInfo = info
        Log.d(TAG, "KeyHookAccessibilityService Connected & Requesting Key Filtering")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            buttonCallback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
                override fun onClicked(controller: AccessibilityButtonController?) {
                    if (TimerState.isOverlayRunning.value) {
                        val intent = Intent(this@KeyHookAccessibilityService, FloatingTimerService::class.java)
                        intent.action = FloatingTimerService.ACTION_STOP_SERVICE
                        startService(intent)
                    } else {
                        if (Settings.canDrawOverlays(this@KeyHookAccessibilityService)) {
                            startService(Intent(this@KeyHookAccessibilityService, FloatingTimerService::class.java))
                        } else {
                            Toast.makeText(this@KeyHookAccessibilityService, "Overlay permission required to open timer.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            buttonCallback?.let { accessibilityButtonController.registerAccessibilityButtonCallback(it) }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            buttonCallback?.let { accessibilityButtonController.unregisterAccessibilityButtonCallback(it) }
        }
        super.onDestroy()
    }

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