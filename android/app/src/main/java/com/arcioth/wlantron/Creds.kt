package com.arcioth.wlantron

import android.content.Context

/** App-private username and password. The file never leaves this app's storage. */
class Creds(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): Pair<String, String>? {
        val username = prefs.getString(USER, "").orEmpty().trim()
        val password = prefs.getString(PASS, "").orEmpty()
        if (username.isEmpty() || password.isEmpty()) return null
        return username to password
    }

    fun save(username: String, password: String) {
        prefs.edit().putString(USER, username.trim()).putString(PASS, password).apply()
    }

    fun clear() {
        prefs.edit().remove(USER).remove(PASS).apply()
    }

    private companion object {
        const val PREFS = "hho-wifi"
        const val USER = "username"
        const val PASS = "password"
    }
}
