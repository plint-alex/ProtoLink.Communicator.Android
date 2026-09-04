package ru.protolink.communicator.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TokenStore @Inject constructor(@ApplicationContext context: Context) {
    private val gson = Gson()
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "protolink_tokens",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun load(): TokenData? {
        val json = prefs.getString("token", null) ?: return null
        return runCatching { gson.fromJson(json, TokenData::class.java) }.getOrNull()
    }

    fun save(token: TokenData) {
        prefs.edit().putString("token", gson.toJson(token)).apply()
    }

    fun clear() {
        prefs.edit().remove("token").apply()
    }
}

@Singleton
class SettingsStore @Inject constructor(@ApplicationContext context: Context) {
    private val gson = Gson()
    private val prefs = context.getSharedPreferences("protolink_settings", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val json = prefs.getString("settings", null) ?: return AppSettings()
        return runCatching { gson.fromJson(json, AppSettings::class.java) }.getOrDefault(AppSettings())
    }

    fun save(settings: AppSettings) {
        prefs.edit().putString("settings", gson.toJson(settings)).apply()
    }
}

@Singleton
class MappingStore @Inject constructor(@ApplicationContext context: Context) {
    private val gson = Gson()
    private val prefs = context.getSharedPreferences("protolink_mappings", Context.MODE_PRIVATE)

    fun load(): List<CloudSyncMappingDto> {
        val json = prefs.getString("mappings", null) ?: return emptyList()
        val arr = runCatching {
            gson.fromJson(json, Array<CloudSyncMappingDto>::class.java)?.toList()
        }.getOrNull()
        return arr ?: emptyList()
    }

    fun save(mappings: List<CloudSyncMappingDto>) {
        prefs.edit().putString("mappings", gson.toJson(mappings)).apply()
    }
}
