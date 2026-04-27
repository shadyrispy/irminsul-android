package com.github.konkers.irminsul.data

import android.content.Context
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.BufferedReader

class GameDatabaseRepository(private val context: Context) {

    private val _database = mutableStateOf<GameDatabase?>(null)
    val database: State<GameDatabase?> = _database

    private val _loadError = mutableStateOf<String?>(null)
    val loadError: State<String?> = _loadError

    suspend fun loadDatabase() {
        withContext(Dispatchers.IO) {
            try {
                val inputStream = context.assets.open("game_data/database.json")
                val reader = BufferedReader(inputStream.reader())
                val db = Json.decodeFromString<GameDatabase>(reader.readText())
                _database.value = db
                _loadError.value = null
            } catch (e: Exception) {
                _loadError.value = "加载数据库失败: ${e.message}"
            }
        }
    }

    fun getCharacterName(id: String): String {
        return _database.value?.character_map?.get(id) ?: "角色$id"
    }

    fun getWeaponName(id: String): String {
        return _database.value?.weapon_map?.get(id)?.name ?: "武器$id"
    }

    fun getArtifactSetName(setId: String): String {
        return _database.value?.set_map?.get(setId) ?: "套装$setId"
    }

    fun hasData(): Boolean {
        return _database.value != null
    }
}
