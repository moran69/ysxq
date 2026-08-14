package com.momo.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SearchHistoryStore(private val dataStore: DataStore<Preferences>) {

    companion object {
        private val KEY_HISTORY = stringPreferencesKey("search_history")
        private const val SEPARATOR = "\u0000"
        private const val MAX_ENTRIES = 30
    }

    val recentSearches: Flow<List<String>> = dataStore.data.map { prefs ->
        prefs[KEY_HISTORY]?.split(SEPARATOR)?.filter { it.isNotBlank() } ?: emptyList()
    }

    suspend fun addSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        dataStore.edit { prefs ->
            val current = prefs[KEY_HISTORY]?.split(SEPARATOR)?.filter { it.isNotBlank() }
                ?: emptyList()
            val updated = (listOf(trimmed) + current.filter { it != trimmed })
                .take(MAX_ENTRIES)
            prefs[KEY_HISTORY] = updated.joinToString(SEPARATOR)
        }
    }

    suspend fun removeSearch(query: String) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_HISTORY]?.split(SEPARATOR)?.filter { it.isNotBlank() }
                ?: emptyList()
            prefs[KEY_HISTORY] = current.filter { it != query }.joinToString(SEPARATOR)
        }
    }

    suspend fun clearAll() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_HISTORY)
        }
    }
}

private val Context.searchHistoryDataStore by preferencesDataStore(name = "search_history")

fun Context.searchHistoryStore(): SearchHistoryStore {
    return SearchHistoryStore(searchHistoryDataStore)
}
