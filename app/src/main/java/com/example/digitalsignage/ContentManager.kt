package com.example.digitalsignage

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.URL

class ContentManager(private val context: Context) {

    private val TAG = "ContentManager"
    private val PLAYLIST_FILENAME = "playlist.json"

    // Base URL for raw content
    private val REMOTE_BASE_URL = "https://raw.githubusercontent.com/tonyddayvd/tv-playlist/main/playlists"

    suspend fun syncContent(clientId: String): PlaylistData {
        return withContext(Dispatchers.IO) {
            try {
                // 1. Download Playlist JSON (Dynamic based on Client ID)
                val url = "$REMOTE_BASE_URL/$clientId.json?t=${System.currentTimeMillis()}"
                
                val jsonString = URL(url).readText()
                savePlaylistLocally(jsonString)
                
                // 2. Parse
                val data = parsePlaylist(jsonString)
                
                // 3. Download Media Files
                val downloadedItems = data.items.map { item ->
                    downloadFileIfNeeded(item)
                }
                
                data.copy(items = downloadedItems)
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing: ${e.message}")
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Modo Offline: Usando playlist salva...", android.widget.Toast.LENGTH_LONG).show()
                }
                // Fallback to local
                loadLocalPlaylist()
            }
        }
    }

    suspend fun fetchClients(): List<ClientItem> {
        return withContext(Dispatchers.IO) {
            try {
                // Cache busting
                val url = "$REMOTE_BASE_URL/clients.json?t=${System.currentTimeMillis()}"
                val jsonString = URL(url).readText()
                
                val jsonArray = org.json.JSONArray(jsonString)
                val clients = mutableListOf<ClientItem>()
                
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    clients.add(ClientItem(
                        id = obj.getString("id"),
                        name = obj.getString("name")
                    ))
                }
                clients
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching clients: ${e.message}")
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Erro Clientes: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
                emptyList()
            }
        }
    }

    private fun parsePlaylist(jsonString: String): PlaylistData {
        val root = org.json.JSONObject(jsonString)
        
        // Parse Settings
        val settingsJson = root.optJSONObject("settings")
        val settings = if (settingsJson != null) {
            Settings(
                orientation = settingsJson.optString("orientation", "landscape"),
                transitionDuration = settingsJson.optInt("transitionDuration", 1000)
            )
        } else {
            Settings()
        }

        // Parse Playlist
        val list = mutableListOf<PlaylistItem>()
        val jsonArray = root.optJSONArray("playlist")
        if (jsonArray != null) {
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    PlaylistItem(
                        id = obj.getString("id"),
                        type = detectTypeFromExtension(obj.getString("url"), obj.getString("type")),
                        url = obj.getString("url"),
                        durationSeconds = obj.optInt("duration", 10)
                    )
                )
            }
        }
        return PlaylistData(settings, list)
    }

    private fun downloadFileIfNeeded(item: PlaylistItem): PlaylistItem {
        // Sanitize filename: Remove query params (?) and decode %20 to spaces
        val rawFileName = item.url.substringAfterLast("/").substringBefore("?")
        val fileName = java.net.URLDecoder.decode(rawFileName, "UTF-8")
        val file = File(context.filesDir, fileName)
        
        if (!file.exists()) {
            try {
                URL(item.url).openStream().use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download ${item.url}")
                return item // Return without local path if failed
            }
        }
        return item.copy(localPath = file.absolutePath)
    }

    private fun savePlaylistLocally(json: String) {
        context.openFileOutput(PLAYLIST_FILENAME, Context.MODE_PRIVATE).use {
            it.write(json.toByteArray())
        }
    }

    private fun loadLocalPlaylist(): PlaylistData {
        val file = File(context.filesDir, PLAYLIST_FILENAME)
        if (file.exists()) {
            val data = parsePlaylist(file.readText())
            // CRITICAL: Resolve local paths for offline playback
            val resolvedItems = data.items.map { item ->
                downloadFileIfNeeded(item)
            }
            return data.copy(items = resolvedItems)
        }
        return PlaylistData(Settings(), emptyList())
    }
    private fun detectTypeFromExtension(url: String, declaredType: String): String {
        val lowerUrl = url.lowercase()
        if (lowerUrl.endsWith(".mp4") || lowerUrl.endsWith(".mkv") || lowerUrl.endsWith(".webm")) {
            return "video"
        }
        if (lowerUrl.endsWith(".jpg") || lowerUrl.endsWith(".jpeg") || lowerUrl.endsWith(".png")) {
            return "image"
        }
        return declaredType // Fallback to JSON type
    }
}

data class ClientItem(val id: String, val name: String) {
    override fun toString(): String {
        return name // For Spinner display
    }
}
