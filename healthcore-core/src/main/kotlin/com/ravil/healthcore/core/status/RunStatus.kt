package com.ravil.healthcore.core.status

import java.time.Duration
import java.time.Instant

/**
 * What the main screen shows about the last run of one job: when, how it ended and how many
 * records it moved. Codes and counts only; no health values ever reach this type.
 */
data class RunStatus(
    val at: Instant,
    val code: String,
    val ok: Boolean,
    val records: Int = 0,
    val errors: Int = 0,
)

object StatusText {
    /** A short English label for a run code (codes come from the sync code, never from a server body). */
    fun label(code: String): String = when (code) {
        "delivered" -> "Delivered"
        "no_data" -> "Nothing new"
        "ok" -> "Up to date"
        "ok_with_errors" -> "Done with errors"
        "unauthorized" -> "Token rejected"
        "rejected" -> "Rejected by server"
        "payload_too_large" -> "Too much data at once"
        "too_many_records" -> "Too many records at once"
        "rate_limited" -> "Rate limited, will retry"
        "server_error" -> "Server error, will retry"
        "network_error" -> "Network error, will retry"
        "no_token" -> "No token saved"
        "invalid_url" -> "Server URL is not https"
        "hc_not_available" -> "Health Connect not available"
        "no_permissions" -> "No Health Connect permissions"
        "read_failed" -> "Reading Health Connect failed"
        "out_of_memory" -> "Out of memory while reading"
        "fetch_error" -> "Could not fetch the queue"
        "missing_write_nutrition_permission" -> "Nutrition write permission missing"
        "sync_disabled" -> "Meal sync is off"
        "settings_pending" -> "Settings are being saved"
        else -> code.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    /** "just now", "5 min ago", "3 h ago", "2 d ago". */
    fun ago(then: Instant, now: Instant): String {
        val d = Duration.between(then, now)
        return when {
            d.isNegative || d.seconds < 60 -> "just now"
            d.toMinutes() < 60 -> "${d.toMinutes()} min ago"
            d.toHours() < 48 -> "${d.toHours()} h ago"
            else -> "${d.toDays()} d ago"
        }
    }

    /** The token row in settings: never the token, only whether one is stored and since when. */
    fun tokenState(saved: Boolean, updatedAt: Instant?, now: Instant): String = when {
        !saved -> "Not set"
        updatedAt == null -> "Saved"
        else -> "Saved · updated ${ago(updatedAt, now)}"
    }
}
