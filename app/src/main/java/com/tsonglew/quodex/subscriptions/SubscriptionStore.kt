package com.tsonglew.quodex.subscriptions

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.tsonglew.quodex.data.*
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SubscriptionStore {
    fun read(): Map<SubscriptionProvider, SubscriptionAccount>
    fun write(accounts: Map<SubscriptionProvider, SubscriptionAccount>)
}

/** Encrypt credentials and their matching cache as a single atomic transaction. */
class EncryptedSubscriptionStore(context: Context) : SubscriptionStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "subscriptions.enc"))
    private val alias = "quodex.subscriptions.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    @Synchronized override fun read(): Map<SubscriptionProvider, SubscriptionAccount> {
        return try {
            // Let AtomicFile restore its backup after an interrupted write.
            val data = file.readFully()
            require(data.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
            AppJson.decodeFromString(String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8))
        } catch (_: FileNotFoundException) {
            if (file.baseFile.exists()) throw ApiFailure(FailureKind.STORAGE)
            emptyMap()
        }
        catch (_: Exception) { throw ApiFailure(FailureKind.STORAGE) }
    }
    @Synchronized override fun write(accounts: Map<SubscriptionProvider, SubscriptionAccount>) {
        if (accounts.isEmpty()) { file.delete(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = cipher.iv + cipher.doFinal(AppJson.encodeToString(accounts).toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try { stream.write(data); file.finishWrite(stream) }
        catch (failure: Exception) { file.failWrite(stream); throw failure }
    }
}
