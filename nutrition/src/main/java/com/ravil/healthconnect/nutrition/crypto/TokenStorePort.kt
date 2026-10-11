package com.ravil.healthconnect.nutrition.crypto

interface TokenStorePort {
    fun getToken(): String?
    /** Returns true only when the token reached durable storage. */
    fun setToken(token: String): Boolean
    /** Returns true only when the removal reached durable storage. */
    fun clearToken(): Boolean
}
