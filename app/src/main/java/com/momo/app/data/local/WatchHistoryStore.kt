package com.momo.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@kotlinx.serialization.Serializable
data class WatchHistoryEntry(
    val videoId: Int,
    val videoName: String,
    val pic: String,
    val typeName: String = "",
    val remarks: String = "",
    val episodeIndex: Int = 0,
    val episodeName: String = "",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis()
)

class WatchHistoryStore(private val dataStore: DataStore<Preferences>) {

    companion object {
        private val KEY_HISTORY = stringPreferencesKey("watch_history_json")
        private val json = Json { ignoreUnknownKeys = true }
        private const val MAX_ENTRIES = 100
    }

    val history: Flow<List<WatchHistoryEntry>> = dataStore.data.map { prefs ->
        val raw = prefs[KEY_HISTORY] ?: "[]"
        runCatching { json.decodeFromString<List<WatchHistoryEntry>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun saveProgress(entry: WatchHistoryEntry) {
        dataStore.edit { prefs ->
            val current = runCatching {
                json.decodeFromString<List<WatchHistoryEntry>>(prefs[KEY_HISTORY] ?: "[]")
            }.getOrDefault(emptyList()).toMutableList()

            current.removeAll { it.videoId == entry.videoId }
            current.add(0, entry.copy(updatedAt = System.currentTimeMillis()))

            if (current.size > MAX_ENTRIES) {
                prefs[KEY_HISTORY] = json.encodeToString(current.take(MAX_ENTRIES))
            } else {
                prefs[KEY_HISTORY] = json.encodeToString(current)
            }
        }
    }

    suspend fun removeHistory(videoId: Int) {
        dataStore.edit { prefs ->
            val current = runCatching {
                json.decodeFromString<List<WatchHistoryEntry>>(prefs[KEY_HISTORY] ?: "[]")
            }.getOrDefault(emptyList())
            prefs[KEY_HISTORY] = json.encodeToString(current.filter { it.videoId != videoId })
        }
    }

    suspend fun replaceAll(entries: List<WatchHistoryEntry>) {
        dataStore.edit { prefs ->
            val sorted = entries.sortedByDescending { it.updatedAt }
            prefs[KEY_HISTORY] = json.encodeToString(sorted.take(MAX_ENTRIES))
        }
    }

    suspend fun clearAll() {
        dataStore.edit { prefs ->
            prefs[KEY_HISTORY] = "[]"
        }
    }

    fun getEntryFlow(videoId: Int): Flow<WatchHistoryEntry?> = dataStore.data.map { prefs ->
        val raw = prefs[KEY_HISTORY] ?: "[]"
        val list = runCatching { json.decodeFromString<List<WatchHistoryEntry>>(raw) }.getOrDefault(emptyList())
        list.find { it.videoId == videoId }
    }
}

private val Context.watchHistoryDataStore by preferencesDataStore(name = "watch_history")
fun Context.watchHistoryStore(): WatchHistoryStore = WatchHistoryStore(watchHistoryDataStore)
