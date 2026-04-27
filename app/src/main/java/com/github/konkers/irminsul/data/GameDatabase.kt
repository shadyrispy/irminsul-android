package com.github.konkers.irminsul.data

import kotlinx.serialization.Serializable

/** 对应 database.json 的顶层结构 */
@Serializable
data class GameDatabase(
    val version: Int = 1,
    val git_hash: String = "",
    val affix_map: Map<String, Affix> = emptyMap(),
    val artifact_map: Map<String, Artifact> = emptyMap(),
    val character_map: Map<String, String> = emptyMap(),
    val material_map: Map<String, String> = emptyMap(),
    val property_map: Map<String, String> = emptyMap(),
    val set_map: Map<String, String> = emptyMap(),
    val skill_type_map: Map<String, String> = emptyMap(),
    val weapon_map: Map<String, Weapon> = emptyMap()
)

@Serializable
data class Affix(
    val property: String,
    val value: Double
)

@Serializable
data class Artifact(
    val set: String,
    val slot: String,
    val rarity: Int
)

@Serializable
data class Weapon(
    val name: String,
    val rarity: Int
)
