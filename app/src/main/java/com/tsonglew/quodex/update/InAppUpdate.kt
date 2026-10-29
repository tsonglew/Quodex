package com.tsonglew.quodex.update

import com.tsonglew.quodex.i18n.*
import com.tsonglew.quodex.R
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException
import java.security.MessageDigest

enum class InstallStage { IDLE, DOWNLOADING, VERIFYING, READY, ERROR }

data class InstallState(
    val stage: InstallStage = InstallStage.IDLE,
    val update: AppUpdate? = null,
    val received: Long = 0,
    val total: Long = 0,
    val message: UiText? = null,
    val visible: Boolean = false,
) {
    val busy: Boolean get() = stage == InstallStage.DOWNLOADING || stage == InstallStage.VERIFYING
}

/** Application-owned so tab changes and activity recreation do not restart a download. */
class InAppUpdate(
    private val context: Context,
    private val scope: CoroutineScope,
    private val source: UpdateSource,
    private val downloader: ApkDownloader = ApkDownloader(),
) {
    private val mutableState = MutableStateFlow(InstallState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var readyFile: File? = null
    private val directory get() = File(context.cacheDir, "updates")

    @Synchronized fun start(update: AppUpdate) {
        if (job?.isCompleted == false || state.value.stage == InstallStage.READY && state.value.update == update) {
            mutableState.update { it.copy(visible = true) }
            return
        }
        readyFile = null
        mutableState.value = InstallState(InstallStage.DOWNLOADING, update, visible = true)
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                // Migrate metadata cached by old versions without guessing an APK URL or checksum.
                val target = if (update.apkSha256 != null && trustedApkUrl(update) != null) update
                    else source.latest()?.takeIf { it.versionCode >= update.versionCode }
                        ?: throw UserVisibleException(UiText(R.string.version_details_have_changed_check_for_updates))
                val checksum = target.apkSha256?.takeIf { Regex("[0-9a-f]{64}").matches(it) }
                    ?: throw UserVisibleException(UiText(R.string.this_release_has_no_apk_checksum_check))
                mutableState.update { it.copy(update = target, total = target.apkSize) }
                directory.mkdirs()
                val file = File(directory, "update-${target.versionCode}-${checksum.take(12)}.apk")
                directory.listFiles()?.filter { it != file }?.forEach { it.delete() }
                // A previous process may have completed the download before it was closed.
                val cached = file.isFile && runCatching { validateDownloadedApk(context, file, target) }.isSuccess
                if (!cached) {
                    file.delete()
                    downloader.download(target, file) { received, total ->
                        mutableState.update { it.copy(received = received, total = total) }
                    }
                }
                mutableState.update { it.copy(stage = InstallStage.VERIFYING) }
                try { validateDownloadedApk(context, file, target) }
                catch (failure: Exception) { file.delete(); throw failure }
                ensureActive()
                readyFile = file
                mutableState.update { it.copy(stage = InstallStage.READY, received = target.apkSize, message = null) }
            } catch (cancel: CancellationException) {
                mutableState.update { it.copy(stage = InstallStage.IDLE, received = 0, message = UiText(R.string.download_canceled)) }
                throw cancel
            } catch (failure: Exception) {
                mutableState.update { it.copy(stage = InstallStage.ERROR, message = when (failure) {
                    is UserVisibleException -> failure.text
                    else -> null
                } ?: UiText(R.string.download_incomplete_check_your_network_and_storage)) }
            }
        }.also { it.start() }
    }

    fun dismiss() { mutableState.update { it.copy(visible = false) } }
    fun cancel() { job?.cancel() }
    fun report(message: UiText) { mutableState.update { it.copy(message = message) } }

    suspend fun installIntent(): Intent? = withContext(Dispatchers.IO) {
        val file = readyFile ?: return@withContext null
        val update = state.value.update ?: return@withContext null
        try {
            validateDownloadedApk(context, file, update)
            apkInstallIntent(context, file)
        } catch (_: Exception) {
            file.delete()
            readyFile = null
            mutableState.update { it.copy(stage = InstallStage.ERROR, message = UiText(R.string.the_apk_is_no_longer_valid_download)) }
            null
        }
    }
}

internal fun validateDownloadedApk(context: Context, file: File, update: AppUpdate) {
    if (file.length() != update.apkSize) throw UserVisibleException(UiText(R.string.the_apk_size_does_not_match_download))
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) { val count = input.read(buffer); if (count == -1) break; digest.update(buffer, 0, count) }
    }
    if (update.apkSha256 == null || digest.digest().hex() != update.apkSha256)
        throw UserVisibleException(UiText(R.string.apk_verification_failed_download_it_again))
    val manager = context.packageManager
    val archive = manager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        ?: throw UserVisibleException(UiText(R.string.could_not_read_the_apk_download_it))
    val installed = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    if (archive.packageName != context.packageName || archive.longVersionCode != update.versionCode ||
        archive.longVersionCode <= installed.longVersionCode)
        throw UserVisibleException(UiText(R.string.the_apk_does_not_match_this_app))
    val signers = archive.signingInfo?.apkContentsSigners?.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }
    if (signers != listOf(installedSigningCertificate(context)))
        throw UserVisibleException(UiText(R.string.the_apk_signature_does_not_match_it))
}

internal fun apkInstallIntent(context: Context, file: File): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
    return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .apply { clipData = ClipData.newRawUri("Quodex update", uri) }
}

class UpdateFileProvider : FileProvider()
