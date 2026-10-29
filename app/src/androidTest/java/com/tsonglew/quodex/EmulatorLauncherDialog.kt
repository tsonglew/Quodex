package com.tsonglew.quodex

import android.app.Instrumentation
import android.view.accessibility.AccessibilityNodeInfo
import java.io.FileInputStream

internal enum class LauncherAnrResponse(val button: String) { CLOSE("Close app"), WAIT("Wait") }

/** Recover one known external emulator dialog; never dismiss an App failure. */
internal fun dismissLauncherAnrOnEmulator(
    instrumentation: Instrumentation,
    response: LauncherAnrResponse = LauncherAnrResponse.CLOSE,
): Boolean {
    val automation = instrumentation.uiAutomation
    val root = automation.rootInActiveWindow ?: return false
    if (root.packageName?.toString() != "android") return false
    fun descendants(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) +
        (0 until node.childCount).flatMap { node.getChild(it)?.let(::descendants).orEmpty() }
    val nodes = descendants(root)
    val emulator = automation.executeShellCommand("getprop ro.kernel.qemu").use {
        FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText().trim() }
    }
    if (emulator != "1") return false
    val context = instrumentation.targetContext
    val home = android.content.Intent(android.content.Intent.ACTION_MAIN)
        .addCategory(android.content.Intent.CATEGORY_HOME)
    val launcher = context.packageManager.resolveActivity(home, 0)?.activityInfo ?: return false
    val title = when (launcher.packageName) {
        "com.android.launcher3" -> "Quickstep isn't responding"
        "com.google.android.apps.nexuslauncher" -> "Pixel Launcher isn't responding"
        else -> return false
    }
    if (launcher.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0) return false
    val titles = nodes.mapNotNull { it.text?.toString()?.replace('’', '\'') }
    val systemUi = "System UI isn't responding" in titles
    if (systemUi) {
        val info = runCatching { context.packageManager.getApplicationInfo("com.android.systemui", 0) }.getOrNull()
            ?: return false
        if (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0) return false
    } else if (title !in titles) return false
    // Preserve System UI and its pending launcher dialog; never close it or a Quodex ANR.
    val buttonText = if (systemUi) LauncherAnrResponse.WAIT.button else response.button
    val action = nodes.singleOrNull { it.text?.toString() == buttonText } ?: return false
    val button = generateSequence(action) { it.parent }.firstOrNull { it.isClickable } ?: return false
    automation.takeScreenshot()?.let { saveDeviceScreenshot(context, "emulator-launcher-anr", it) }
    android.util.Log.w("QuotaDeviceEnvironment", "$buttonText for known system ANR on disposable emulator: " +
        "${if (systemUi) "com.android.systemui" else launcher.packageName}; screenshot retained")
    check(button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Cannot respond to emulator launcher dialog" }
    return true
}
