package com.reelay.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.reelay.app.data.PreferencesManager
import com.reelay.app.ui.AuthActivity

class ReelayAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var isRunning = false
    }

    private lateinit var prefs: PreferencesManager
    private val handler = Handler(Looper.getMainLooper())
    private var lastActionTimestamp = 0L
    private val DEBOUNCE_DELAY_MS = 300L

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesManager(this)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 40
        }
        this.serviceInfo = info
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: return

        val now = System.currentTimeMillis()
        if (now - lastActionTimestamp < DEBOUNCE_DELAY_MS) {
            return
        }

        // Resolve top root window
        var actualRoot = rootInActiveWindow
        if (actualRoot == null && event.source != null) {
            var curr: AccessibilityNodeInfo? = event.source
            while (curr?.parent != null) {
                curr = curr.parent
            }
            actualRoot = curr
        }

        val eventDesc = event.contentDescription?.toString() ?: ""
        val eventText = event.text.joinToString(" ")
        val isClick = event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED

        when (packageName) {
            // 1. TikTok
            "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.zhiliaoapp.musically.go" -> {
                if (prefs.blockTikTok) {
                    lastActionTimestamp = now
                    showBlockedToast("Reelay: TikTok is Blocked")
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    prefs.incrementBlockedCount()
                }
            }

            // 2. YouTube (Blocks Shorts from tab, feed, search results, or links)
            "com.google.android.youtube", "com.google.android.apps.youtube.mango" -> {
                if (prefs.blockYouTubeShorts) {
                    val clickedShorts = isClick && (
                            eventDesc.contains("Short", ignoreCase = true) ||
                            eventText.contains("Short", ignoreCase = true) ||
                            eventDesc.contains("play Short", ignoreCase = true)
                    )

                    if (clickedShorts || (actualRoot != null && detectYouTubeShorts(actualRoot))) {
                        lastActionTimestamp = now
                        showBlockedToast("Reelay: YouTube Shorts Blocked")

                        // Exits the Shorts player back to search results or feed
                        performGlobalAction(GLOBAL_ACTION_BACK)

                        // If user is on bottom navigation Shorts tab, navigate to Home
                        actualRoot?.let { clickBottomHomeTab(it, this) }

                        prefs.incrementBlockedCount()
                    }
                }
            }

            // 3. Instagram
            "com.instagram.android", "com.instagram.lite" -> {
                if (prefs.blockInstaReels) {
                    val clickedReels = isClick && (eventDesc.contains("Reel", ignoreCase = true) || eventText.contains("Reel", ignoreCase = true))

                    if (clickedReels || (actualRoot != null && detectInstagramReels(actualRoot))) {
                        lastActionTimestamp = now
                        showBlockedToast("Reelay: Instagram Reels Blocked")
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        actualRoot?.let { clickBottomHomeTab(it, this) }
                        prefs.incrementBlockedCount()
                    }
                }
            }

            // 4. Facebook
            "com.facebook.katana", "com.facebook.lite", "com.facebook.orca" -> {
                if (prefs.blockFacebookReels && actualRoot != null) {
                    val clickedReels = isClick && (eventDesc.contains("Reel", ignoreCase = true) || eventText.contains("Reel", ignoreCase = true))

                    if (clickedReels || detectFacebookReels(actualRoot)) {
                        lastActionTimestamp = now
                        showBlockedToast("Reelay: Facebook Reels Blocked")
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        clickBottomHomeTab(actualRoot, this)
                        prefs.incrementBlockedCount()
                    }
                }
            }

            // 5. System Settings / App Info Uninstall Protection
            "com.android.settings", "com.google.android.packageinstaller", "com.android.packageinstaller", "com.miui.securitycenter" -> {
                if (prefs.antiUninstallProtection && !prefs.isAuthRecent() && actualRoot != null) {
                    if (isReelayAppInfoOrAdminActive(actualRoot)) {
                        lastActionTimestamp = now
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        val authIntent = Intent(this, AuthActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            putExtra(AuthActivity.EXTRA_REASON, AuthActivity.REASON_SETTINGS_PROTECTION)
                        }
                        startActivity(authIntent)
                    }
                }
            }
        }
    }

    private fun detectYouTubeShorts(root: AccessibilityNodeInfo): Boolean {
        // 1. Direct ContentDescription indicators for active Shorts player / search item
        val shortsContentDesc = listOf(
            "play Short",
            "Remix this video",
            "Sound used in this Shorts",
            "Shorts playback",
            "Shorts player",
            "YouTube Shorts",
            "Use this sound"
        )
        if (hasAnyContentDescriptionSubstring(root, shortsContentDesc, maxDepth = 20)) {
            return true
        }

        // 2. View ID substrings (deep scan up to depth 20)
        val shortsViewIds = listOf(
            "shorts_container",
            "shorts_player",
            "reel_player",
            "reel_recycler",
            "shorts_video",
            "reel_watch",
            "shorts_shelf",
            "reel_view"
        )
        if (hasAnyViewIdSubstring(root, shortsViewIds, maxDepth = 20)) {
            return true
        }

        // 3. Bottom tab check (if on standalone Shorts tab)
        val shortsNodes = root.findAccessibilityNodeInfosByText("Shorts")
        if (shortsNodes.isNotEmpty()) {
            for (node in shortsNodes) {
                val desc = node.contentDescription?.toString() ?: ""
                if (desc.contains("selected", ignoreCase = true) || node.isSelected) {
                    return true
                }
            }
        }

        // 4. Check for Remix + (Like / Dislike / Share / Subscribe)
        if (root.findAccessibilityNodeInfosByText("Remix").isNotEmpty() ||
            root.findAccessibilityNodeInfosByText("Shorts").isNotEmpty()) {
            val hasInteractions = root.findAccessibilityNodeInfosByText("Dislike").isNotEmpty() ||
                    root.findAccessibilityNodeInfosByText("Share").isNotEmpty() ||
                    root.findAccessibilityNodeInfosByText("Subscribe").isNotEmpty() ||
                    root.findAccessibilityNodeInfosByText("Subscribed").isNotEmpty()
            if (hasInteractions) {
                return true
            }
        }

        return false
    }

    private fun detectInstagramReels(root: AccessibilityNodeInfo): Boolean {
        val hasReels = root.findAccessibilityNodeInfosByText("Reels").isNotEmpty() ||
                root.findAccessibilityNodeInfosByText("Reel").isNotEmpty()

        val hasReelsIndicators = root.findAccessibilityNodeInfosByText("Follow").isNotEmpty() ||
                root.findAccessibilityNodeInfosByText("Original audio").isNotEmpty() ||
                root.findAccessibilityNodeInfosByText("Use audio").isNotEmpty() ||
                root.findAccessibilityNodeInfosByText("Audio is muted").isNotEmpty() ||
                root.findAccessibilityNodeInfosByText("Remix").isNotEmpty() ||
                root.findAccessibilityNodeInfosByText("Watch Reels").isNotEmpty()

        if (hasReels && hasReelsIndicators) {
            return true
        }

        if (root.findAccessibilityNodeInfosByText("Original audio").isNotEmpty()) return true
        if (root.findAccessibilityNodeInfosByText("Use audio").isNotEmpty()) return true
        if (root.findAccessibilityNodeInfosByText("Audio is muted").isNotEmpty()) return true

        val reelsViewIds = listOf(
            "clips_video_container",
            "reels_video_view",
            "clips_viewer",
            "reel_viewer",
            "clips_video_root_view"
        )
        return hasAnyViewIdSubstring(root, reelsViewIds, maxDepth = 20)
    }

    private fun detectFacebookReels(root: AccessibilityNodeInfo): Boolean {
        val hasReels = root.findAccessibilityNodeInfosByText("Reels").isNotEmpty() ||
                root.findAccessibilityNodeInfosByText("Reel").isNotEmpty()

        val hasFacebookIndicators = root.findAccessibilityNodeInfosByText("Follow").isNotEmpty() ||
                root.findAccessibilityNodeInfosByText("Comment").isNotEmpty() ||
                root.findAccessibilityNodeInfosByText("Share").isNotEmpty() ||
                root.findAccessibilityNodeInfosByText("Friends").isNotEmpty() ||
                root.findAccessibilityNodeInfosByText("Original audio").isNotEmpty()

        if (hasReels && hasFacebookIndicators) {
            return true
        }

        if (root.findAccessibilityNodeInfosByText("Original audio").isNotEmpty()) return true
        if (root.findAccessibilityNodeInfosByText("Remix").isNotEmpty()) return true

        return false
    }

    /**
     * Safely clicks the bottom-left Home icon without ever touching the top header dropdown.
     */
    private fun clickBottomHomeTab(root: AccessibilityNodeInfo, context: Context): Boolean {
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        // 1. Search for Home tab nodes located strictly in the bottom 30% of the screen
        val homeNodes = root.findAccessibilityNodeInfosByText("Home")
        for (node in homeNodes) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            // Ensure this is in the bottom navigation bar (bottom 30%, left 40%)
            if (bounds.top > screenHeight * 0.70f && bounds.left < screenWidth * 0.40f) {
                if (node.isClickable) {
                    return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                var parent = node.parent
                while (parent != null) {
                    if (parent.isClickable) {
                        return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                    parent = parent.parent
                }
            }
        }

        // 2. Direct hardware gesture tap at the bottom-left Home tab coordinates
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val tapX = screenWidth * 0.10f
            val tapY = screenHeight * 0.96f
            val path = Path().apply {
                moveTo(tapX, tapY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()
            dispatchGesture(gesture, null, null)
            return true
        }

        return false
    }

    private fun hasAnyContentDescriptionSubstring(
        node: AccessibilityNodeInfo?,
        substrings: List<String>,
        depth: Int = 0,
        maxDepth: Int = 20
    ): Boolean {
        if (node == null || depth > maxDepth) return false

        val desc = node.contentDescription?.toString()
        if (desc != null) {
            for (sub in substrings) {
                if (desc.contains(sub, ignoreCase = true)) {
                    return true
                }
            }
        }

        val count = node.childCount
        for (i in 0 until count) {
            val child = node.getChild(i)
            if (hasAnyContentDescriptionSubstring(child, substrings, depth + 1, maxDepth)) {
                return true
            }
        }
        return false
    }

    private fun hasAnyViewIdSubstring(
        node: AccessibilityNodeInfo?,
        substrings: List<String>,
        depth: Int = 0,
        maxDepth: Int = 20
    ): Boolean {
        if (node == null || depth > maxDepth) return false

        val viewId = node.viewIdResourceName
        if (viewId != null) {
            for (sub in substrings) {
                if (viewId.contains(sub, ignoreCase = true)) {
                    return true
                }
            }
        }

        val count = node.childCount
        for (i in 0 until count) {
            val child = node.getChild(i)
            if (hasAnyViewIdSubstring(child, substrings, depth + 1, maxDepth)) {
                return true
            }
        }
        return false
    }

    private fun isReelayAppInfoOrAdminActive(root: AccessibilityNodeInfo): Boolean {
        val appNodes = root.findAccessibilityNodeInfosByText("Reelay")
        if (appNodes.isNotEmpty()) return true
        val pkgNodes = root.findAccessibilityNodeInfosByText("com.reelay.app")
        return pkgNodes.isNotEmpty()
    }

    private fun showBlockedToast(message: String) {
        handler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onInterrupt() {}
}
