package com.ysxq.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ysxq.app.data.VideoItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 收藏视频数据模型 — 只保留列表展示所需的最少字段
 */
@kotlinx.serialization.Serializable
data class FavoriteItem(
    val id: Int,
    val name: String,
    val pic: String,
    val typeName: String = "",
    val remarks: String = "",
    val year: String = "",
    val area: String = "",
    val addedAt: Long = System.currentTimeMillis()
)

/**
 * 收藏存储，基于 DataStore Preferences + JSON 序列化
 */
class FavoritesStore(private val dataStore: DataStore<Preferences>) {

    companion object {
        private val KEY_FAVORITES = stringPreferencesKey("favorites_json")
        private val json = Json { ignoreUnknownKeys = true }
    }

    val favorites: Flow<List<FavoriteItem>> = dataStore.data.map { prefs ->
        val raw = prefs[KEY_FAVORITES] ?: "[]"
        runCatching { json.decodeFromString<List<FavoriteItem>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun addFavorite(item: FavoriteItem) {
        dataStore.edit { prefs ->
            val current = runCatching {
                json.decodeFromString<List<FavoriteItem>>(prefs[KEY_FAVORITES] ?: "[]")
            }.getOrDefault(emptyList())
            if (current.none { it.id == item.id }) {
                prefs[KEY_FAVORITES] = json.encodeToString(listOf(item) + current)
            }
        }
    }

    suspend fun removeFavorite(videoId: Int) {
        dataStore.edit { prefs ->
            val current = runCatching {
                json.decodeFromString<List<FavoriteItem>>(prefs[KEY_FAVORITES] ?: "[]")
            }.getOrDefault(emptyList())
            prefs[KEY_FAVORITES] = json.encodeToString(current.filter { it.id != videoId })
        }
    }

    fun isFavoriteFlow(videoId: Int): Flow<Boolean> = dataStore.data.map { prefs ->
        val raw = prefs[KEY_FAVORITES] ?: "[]"
        val list = runCatching { json.decodeFromString<List<FavoriteItem>>(raw) }.getOrDefault(emptyList())
        list.any { it.id == videoId }
    }
}

fun VideoItem.toFavoriteItem(): FavoriteItem = FavoriteItem(
    id = id, name = name, pic = pic,
    typeName = typeName, remarks = remarks,
    year = year, area = area
)

private val Context.favoritesDataStore by preferencesDataStore(name = "favorites")
fun Context.favoritesStore(): FavoritesStore = FavoritesStore(favoritesDataStore)
