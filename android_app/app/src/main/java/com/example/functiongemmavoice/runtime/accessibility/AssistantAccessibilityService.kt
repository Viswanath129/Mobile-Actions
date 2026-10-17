package com.example.functiongemmavoice.runtime.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import java.lang.ref.WeakReference

/**
 * AssistantAccessibilityService provides platform-supported global actions:
 * - GLOBAL_ACTION_LOCK_SCREEN (Android 9+)
 * - GLOBAL_ACTION_BACK (Android 4.1+)
 * - GLOBAL_ACTION_HOME (Android 4.1+)
 * - GLOBAL_ACTION_RECENTS (Android 4.1+)
 * - GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN (Android 7.0+)
 *
 * This service explicitly sets canRetrieveWindowContent="false" to ensure
 * zero screen data or user privacy is intercepted.
 */
class AssistantAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = WeakReference(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Privacy guarantee: No screen content or input is inspected or logged.
    }

    override fun onInterrupt() {
        instance = null
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    companion object {
        private var instance: WeakReference<AssistantAccessibilityService>? = null

        var isServiceRunningForTest: Boolean? = null

        val isServiceRunning: Boolean
            get() = isServiceRunningForTest ?: (instance?.get() != null)

        internal fun setTestInstance(service: AssistantAccessibilityService?) {
            instance = service?.let { WeakReference(it) }
        }

        fun lockScreen(): Boolean {
            val service = instance?.get() ?: return false
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                service.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            } else {
                false
            }
        }

        fun navigateBack(): Boolean {
            val service = instance?.get() ?: return false
            return service.performGlobalAction(GLOBAL_ACTION_BACK)
        }

        fun goHome(): Boolean {
            val service = instance?.get() ?: return false
            return service.performGlobalAction(GLOBAL_ACTION_HOME)
        }

        fun showRecents(): Boolean {
            val service = instance?.get() ?: return false
            return service.performGlobalAction(GLOBAL_ACTION_RECENTS)
        }

        fun toggleSplitScreen(): Boolean {
            val service = instance?.get() ?: return false
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                service.performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
            } else {
                false
            }
        }
    }
}
