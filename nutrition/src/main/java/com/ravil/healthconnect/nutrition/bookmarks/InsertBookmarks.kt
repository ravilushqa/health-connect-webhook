package com.ravil.healthconnect.nutrition.bookmarks

/**
 * Tracks which server records have been successfully inserted into Health Connect.
 * Maps clientRecordId -> highest appliedVersion.
 * Purely functional — Android-free, easy to unit test.
 */
data class InsertBookmarks(private val data: Map<String, Int> = emptyMap()) {

    fun appliedVersion(clientRecordId: String): Int? = data[clientRecordId]

    /** Returns true when the bookmark exists at same or newer version (stale or duplicate). */
    fun isStaleOrDuplicate(clientRecordId: String, incomingVersion: Int): Boolean {
        val existing = data[clientRecordId] ?: return false
        return existing >= incomingVersion
    }

    /** Returns a new InsertBookmarks with the applied record. Never decrements version. */
    fun withApplied(clientRecordId: String, version: Int): InsertBookmarks {
        val existing = data[clientRecordId]
        if (existing != null && existing >= version) return this
        return InsertBookmarks(data + (clientRecordId to version))
    }

    fun toMap(): Map<String, Int> = data

    companion object {
        fun fromMap(map: Map<String, Int>): InsertBookmarks = InsertBookmarks(map)
        val EMPTY = InsertBookmarks()
    }
}
