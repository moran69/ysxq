package com.ysxq.app.data.download

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class DownloadStore(private val dataStore: DataStore<Preferences>) {

    companion object {
        private val KEY_TASKS = stringPreferencesKey("download_tasks_json")
        private val json = Json { ignoreUnknownKeys = true }
    }

    val tasks: Flow<List<DownloadTask>> = dataStore.data.map { prefs ->
        val raw = prefs[KEY_TASKS] ?: "[]"
        runCatching { json.decodeFromString<List<DownloadTask>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun addTask(task: DownloadTask) {
        dataStore.edit { prefs ->
            val current = runCatching {
                json.decodeFromString<List<DownloadTask>>(prefs[KEY_TASKS] ?: "[]")
            }.getOrDefault(emptyList())
            if (current.none { it.id == task.id }) {
                prefs[KEY_TASKS] = json.encodeToString(listOf(task) + current)
            }
        }
    }

    suspend fun updateTask(id: String, transform: (DownloadTask) -> DownloadTask) {
        dataStore.edit { prefs ->
            val current = runCatching {
                json.decodeFromString<List<DownloadTask>>(prefs[KEY_TASKS] ?: "[]")
            }.getOrDefault(emptyList())
            val updated = current.map { task ->
                if (task.id == id) transform(task) else task
            }
            prefs[KEY_TASKS] = json.encodeToString(updated)
        }
    }

    suspend fun deleteTask(id: String) {
        dataStore.edit { prefs ->
            val current = runCatching {
                json.decodeFromString<List<DownloadTask>>(prefs[KEY_TASKS] ?: "[]")
            }.getOrDefault(emptyList())
            val target = current.find { it.id == id }
            if (target != null && target.status == DownloadStatus.COMPLETED.name) {
                val file = File(target.savePath)
                if (file.exists()) file.delete()
                file.parentFile?.takeIf { it.isDirectory && it.listFiles()?.isEmpty() == true }?.delete()
            }
            val tempDir = File(target?.savePath ?: "").parentFile?.resolve(".tmp")?.resolve(id)
            tempDir?.deleteRecursively()
            prefs[KEY_TASKS] = json.encodeToString(current.filter { it.id != id })
        }
    }

    suspend fun getTasksByVideoId(videoId: Int): List<DownloadTask> {
        return tasks.first().filter { it.videoId == videoId }
    }
}

private val Context.downloadDataStore by preferencesDataStore(name = "downloads")
fun Context.downloadStore(): DownloadStore = DownloadStore(downloadDataStore)
