package com.frit.app.core

import android.app.Notification
import android.os.Bundle
import android.os.Parcelable
import android.util.Log

/**
 * Extension function to sanitize notification extras before passing to NotificationManager or startForeground.
 *
 * Prevents ClassCastException in Android framework (Notification.fixDuplicateExtra / fixDuplicateExtras):
 * "java.lang.ClassCastException: java.lang.Integer cannot be cast to android.os.Parcelable"
 *
 * NotificationCompat automatically populates legacy icon/picture extras with Integer resource IDs in notification.extras
 * as well as action.extras for Notification.Action objects.
 * Modern Android framework's fixDuplicateExtras() expects these keys to contain Parcelable objects (e.g., Icon/Bitmap).
 * When an Integer is present in notification.extras or action.extras for these keys,
 * framework's getParcelable() throws ClassCastException.
 */
@Suppress("DEPRECATION")
fun Notification.sanitizeExtras(): Notification {
    try {
        // 1. Sanitize main notification extras
        sanitizeBundle(this.extras)

        // 2. Sanitize action extras (crucial: notification.actions contain action.extras)
        val actions = try { this.actions } catch (_: Exception) { null }
        actions?.forEach { action ->
            if (action != null) {
                sanitizeBundle(action.extras)
            }
        }

        // 3. Sanitize publicVersion extras if present
        val publicVersion = try { this.publicVersion } catch (_: Exception) { null }
        publicVersion?.sanitizeExtras()
    } catch (e: Throwable) {
        Log.e("NotificationSanitizer", "Error sanitizing notification extras", e)
    }
    return this
}

/**
 * Recursively inspects and sanitizes a Bundle to remove non-Parcelable values (such as Integer)
 * stored under keys that Android framework's Notification.fixDuplicateExtra() expects to be Parcelable.
 */
@Suppress("DEPRECATION")
private fun sanitizeBundle(extras: Bundle?) {
    if (extras == null) return
    try {
        val keysToRemove = mutableSetOf<String>()
        val allKeys = try { extras.keySet() } catch (_: Exception) { null } ?: return

        val knownParcelableKeys = setOf(
            "android.icon",
            "android.smallIcon",
            "android.largeIcon",
            "android.largeIcon.big",
            "android.picture",
            "android.pictureIcon",
            "android.backgroundImageUri",
            "android.people",
            "android.people.list",
            "android.mediaSession",
            "android.audioContentsURI",
            "android.slice",
            "android.verificationIcon",
            "android.callPerson",
            "android.support.v4.app.extra.COMPAT_TEMPLATE",
        )

        for (key in allKeys) {
            val value = try { extras.get(key) } catch (_: Exception) { null } ?: continue

            if (value is Bundle) {
                // Recursively sanitize nested sub-bundles (e.g. android.car.EXTENSIONS, android.wearable.EXTENSIONS)
                sanitizeBundle(value)
            } else if ((value !is Parcelable) && (value !is CharSequence)) {
                val isKnownKey = knownParcelableKeys.contains(key)
                val isAndroidKeyWithNumber = (value is Number) &&
                        (key.startsWith("android.") || key.contains("icon", ignoreCase = true) ||
                         key.contains("picture", ignoreCase = true) || key.contains("image", ignoreCase = true))

                if (isKnownKey || isAndroidKeyWithNumber) {
                    keysToRemove.add(key)
                }
            }
        }

        for (key in keysToRemove) {
            extras.remove(key)
        }
    } catch (e: Throwable) {
        Log.e("NotificationSanitizer", "Error sanitizing bundle", e)
    }
}
