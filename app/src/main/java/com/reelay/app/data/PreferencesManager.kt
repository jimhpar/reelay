package com.reelay.app.data

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("reelay_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_PASSWORD_HASH = "key_password_hash"
        private const val KEY_SALT = "key_salt"
        private const val KEY_BLOCK_YT_SHORTS = "key_block_yt_shorts"
        private const val KEY_BLOCK_INSTA_REELS = "key_block_insta_reels"
        private const val KEY_BLOCK_FB_REELS = "key_block_fb_reels"
        private const val KEY_BLOCK_TIKTOK = "key_block_tiktok"
        private const val KEY_STEALTH_MODE = "key_stealth_mode"
        private const val KEY_UNINSTALL_PROTECTION = "key_uninstall_protection"
        private const val KEY_BLOCKED_COUNT = "key_blocked_count"
        private const val KEY_LAST_AUTH_TIME = "key_last_auth_time"
        private const val DEFAULT_SALT = "reelay_secure_salt_2026"
    }

    var isPasswordSet: Boolean
        get() = prefs.contains(KEY_PASSWORD_HASH)
        private set(_) {}

    fun setMasterPassword(password: String) {
        val hash = hashPassword(password)
        prefs.edit().putString(KEY_PASSWORD_HASH, hash).apply()
    }

    fun verifyPassword(password: String): Boolean {
        if (!isPasswordSet) return true
        val savedHash = prefs.getString(KEY_PASSWORD_HASH, null) ?: return true
        val inputHash = hashPassword(password)
        val matches = savedHash == inputHash
        if (matches) {
            updateLastAuthTime()
        }
        return matches
    }

    fun updateLastAuthTime() {
        prefs.edit().putLong(KEY_LAST_AUTH_TIME, System.currentTimeMillis()).apply()
    }

    fun isAuthRecent(gracePeriodMs: Long = 60_000L): Boolean {
        val lastAuth = prefs.getLong(KEY_LAST_AUTH_TIME, 0L)
        return (System.currentTimeMillis() - lastAuth) < gracePeriodMs
    }

    var blockYouTubeShorts: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_YT_SHORTS, true)
        set(value) = prefs.edit().putBoolean(KEY_BLOCK_YT_SHORTS, value).apply()

    var blockInstaReels: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_INSTA_REELS, true)
        set(value) = prefs.edit().putBoolean(KEY_BLOCK_INSTA_REELS, value).apply()

    var blockFacebookReels: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_FB_REELS, true)
        set(value) = prefs.edit().putBoolean(KEY_BLOCK_FB_REELS, value).apply()

    var blockTikTok: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_TIKTOK, true)
        set(value) = prefs.edit().putBoolean(KEY_BLOCK_TIKTOK, value).apply()

    var isStealthMode: Boolean
        get() = prefs.getBoolean(KEY_STEALTH_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_STEALTH_MODE, value).apply()

    var antiUninstallProtection: Boolean
        get() = prefs.getBoolean(KEY_UNINSTALL_PROTECTION, true)
        set(value) = prefs.edit().putBoolean(KEY_UNINSTALL_PROTECTION, value).apply()

    var blockedCount: Int
        get() = prefs.getInt(KEY_BLOCKED_COUNT, 0)
        private set(_) {}

    fun incrementBlockedCount() {
        val current = prefs.getInt(KEY_BLOCKED_COUNT, 0)
        prefs.edit().putInt(KEY_BLOCKED_COUNT, current + 1).apply()
    }

    private fun hashPassword(password: String): String {
        val input = password + DEFAULT_SALT
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
