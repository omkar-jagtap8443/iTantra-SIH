package com.example.itantra.data.prefs

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Contact(
    val deviceId: String,
    val name: String,
    val lastKnownIp: String
)

object ContactsStore {

    private const val PREFS = "itantra_contacts"
    private const val KEY = "contacts_json"

    fun load(context: Context): MutableList<Contact> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        val out = mutableListOf<Contact>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Contact(
                        o.getString("deviceId"),
                        o.getString("name"),
                        o.optString("lastKnownIp", "")
                    )
                )
            }
        } catch (_: Exception) {}
        return out
    }

    fun save(context: Context, contacts: List<Contact>) {
        val arr = JSONArray()
        contacts.forEach {
            arr.put(
                JSONObject().apply {
                    put("deviceId", it.deviceId)
                    put("name", it.name)
                    put("lastKnownIp", it.lastKnownIp)
                }
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, arr.toString())
            .apply()
    }

    fun addOrUpdate(context: Context, contact: Contact) {
        val current = load(context)
        val idx = current.indexOfFirst { it.deviceId == contact.deviceId }
        if (idx >= 0) current[idx] = contact else current.add(contact)
        save(context, current)
    }

    fun remove(context: Context, deviceId: String) {
        val current = load(context).filterNot { it.deviceId == deviceId }
        save(context, current)
    }
}