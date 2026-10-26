package com.tsonglew.quodex.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Entity(tableName = "usage_snapshot")
data class SnapshotRecord(@PrimaryKey val slot: Int = 1, val accountId: String, val json: String)

@Dao
interface SnapshotDao {
    @Query("SELECT * FROM usage_snapshot WHERE slot = 1") suspend fun read(): SnapshotRecord?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun write(record: SnapshotRecord)
    @Query("DELETE FROM usage_snapshot") suspend fun clear()
}

@Database(entities = [SnapshotRecord::class], version = 1, exportSchema = false)
abstract class QuotaDatabase : RoomDatabase() { abstract fun snapshots(): SnapshotDao }

interface CredentialStore {
    fun read(): Session?
    fun write(session: Session)
    fun clear()
}

class CredentialVault(context: Context) : CredentialStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "codex-session.enc"))
    private val alias = "quodex.codex.session.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    @Synchronized override fun read(): Session? {
        if (!file.baseFile.exists()) return null
        return try {
            val data = file.readFully()
            require(data.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
            AppJson.decodeFromString<Session>(String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8))
        } catch (_: Exception) { throw ApiFailure(FailureKind.STORAGE) }
    }
    @Synchronized override fun write(session: Session) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = cipher.iv + cipher.doFinal(AppJson.encodeToString(session).toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try { stream.write(data); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    @Synchronized override fun clear() { file.delete() }
}

private val Context.quotaPreferences by preferencesDataStore("quodex")

data class PreferencesState(
    val refreshMinutes: Long = 30,
    val showRemaining: Boolean = true,
    val theme: String = "system",
    val demo: Boolean = false,
    val lastFailure: FailureKind? = null,
    val retryAt: Long = 0,
    val lastAttemptAt: Long = 0,
    val compactTokens: Boolean = false,
)

interface SyncSettings {
    suspend fun read(): PreferencesState
    suspend fun demo(value: Boolean)
    suspend fun syncResult(kind: FailureKind?, retryAt: Long = 0, lastAttemptAt: Long? = null)
}

class SettingsStore(context: Context) : SyncSettings {
    private val store = context.quotaPreferences
    private val compactTokens = booleanPreferencesKey("compact_tokens")
    suspend fun compactTokens(value: Boolean) { store.edit { it[compactTokens] = value } }
    private val minutes = longPreferencesKey("refresh_minutes")
    private val remaining = booleanPreferencesKey("show_remaining")
    private val theme = stringPreferencesKey("theme")
    private val demo = booleanPreferencesKey("demo")
    private val failure = stringPreferencesKey("last_failure")
    private val retry = longPreferencesKey("retry_at")
    private val attempt = longPreferencesKey("last_attempt_at")
    val flow = store.data.map { p -> PreferencesState(p[minutes] ?: 30, p[remaining] ?: true, p[theme] ?: "system",
        p[demo] ?: false, p[failure]?.let { runCatching { FailureKind.valueOf(it) }.getOrNull() }, p[retry] ?: 0, p[attempt] ?: 0, p[compactTokens] ?: false) }
    override suspend fun read() = flow.first()
    suspend fun refreshMinutes(value: Long) { require(value in listOf(15L, 30L, 60L)); store.edit { it[minutes] = value } }
    suspend fun showRemaining(value: Boolean) {
        store.edit { p ->
            p[remaining] = value
            // A global display change also applies to previously configured widgets.
            // Their themes remain independent; a later widget configuration can override again.
            val widgetRemaining = Regex("widget_\\d+_remaining")
            p.asMap().keys.filter { widgetRemaining.matches(it.name) }.forEach { p.remove(it) }
        }
    }
    suspend fun theme(value: String) { require(value in listOf("system", "light", "dark")); store.edit { it[theme] = value } }
    override suspend fun demo(value: Boolean) { store.edit { it[demo] = value } }
    override suspend fun syncResult(kind: FailureKind?, retryAt: Long, lastAttemptAt: Long?) {
        store.edit { p ->
            if (kind == null) p.remove(failure) else p[failure] = kind.name
            p[retry] = retryAt
            if (lastAttemptAt != null) p[attempt] = lastAttemptAt
        }
    }
    fun widgetSettingsFlow(id: Int) = store.data.map { p ->
        (p[booleanPreferencesKey("widget_${id}_remaining")] ?: p[remaining] ?: true) to
            (p[stringPreferencesKey("widget_${id}_theme")] ?: "system")
    }
    suspend fun widgetSettings(id: Int): Pair<Boolean, String> = widgetSettingsFlow(id).first()
    suspend fun saveWidget(id: Int, showRemaining: Boolean, widgetTheme: String) {
        store.edit { p -> p[booleanPreferencesKey("widget_${id}_remaining")] = showRemaining; p[stringPreferencesKey("widget_${id}_theme")] = widgetTheme }
    }
    suspend fun deleteWidget(id: Int) {
        store.edit { p -> p.asMap().keys.filter { it.name.startsWith("widget_${id}_") }.forEach { p.remove(it) } }
    }
}
