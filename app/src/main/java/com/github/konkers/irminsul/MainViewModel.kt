package com.github.konkers.irminsul

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.github.konkers.irminsul.data.GameDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

sealed class LoadState {
    object Idle : LoadState()
    object Loading : LoadState()
    data class Success(val db: GameDatabase) : LoadState()
    data class Error(val message: String) : LoadState()
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _loadState = MutableStateFlow<LoadState>(LoadState.Idle)
    val loadState: StateFlow<LoadState> = _loadState.asStateFlow()

    fun loadDatabase() {
        if (_loadState.value is LoadState.Loading || _loadState.value is LoadState.Success) return
        _loadState.value = LoadState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val json = getApplication<Application>().assets.open("game_data/database.json")
                    .bufferedReader().use { it.readText() }
                val db = Json { ignoreUnknownKeys = true }.decodeFromString<GameDatabase>(json)
                _loadState.value = LoadState.Success(db)
            } catch (e: Exception) {
                _loadState.value = LoadState.Error(e.message ?: "未知错误")
            }
        }
    }

    fun hasData(): Boolean = _loadState.value is LoadState.Success

    fun getCharacterName(id: String): String {
        val db = (_loadState.value as? LoadState.Success)?.db
        return db?.character_map?.get(id) ?: "角色$id"
    }

    fun getWeaponName(id: String): String {
        val db = (_loadState.value as? LoadState.Success)?.db
        return db?.weapon_map?.get(id)?.name ?: "武器$id"
    }

    fun getArtifactSetName(setId: String): String {
        val db = (_loadState.value as? LoadState.Success)?.db
        return db?.set_map?.get(setId) ?: "套装$setId"
    }

    /** 根据 database.json 内容生成 DataUpdated 状态 */
    fun buildDataUpdated(): DataUpdated {
        val db = (_loadState.value as? LoadState.Success)?.db ?: return DataUpdated()
        return DataUpdated(
            characters = db.character_map?.isNotEmpty() == true,
            items = db.weapon_map?.isNotEmpty() == true || db.artifact_map?.isNotEmpty() == true,
            achievements = false // 成就数据暂未处理
        )
    }
}
