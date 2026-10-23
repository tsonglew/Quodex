package com.tsonglew.quodex

import android.app.Instrumentation
import android.view.accessibility.AccessibilityNodeInfo
import java.io.FileInputStream

/** Recover one known external emulator dialog; never dismiss an App failure. */
internal fun dismissLauncherAnrOnEmulator(instrumentation: Instrumentation): Boolean {
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
    if (nodes.none { it.text?.toString()?.replace('’', '\'') == title }) return false
    val close = nodes.singleOrNull { it.text?.toString() == "Close app" } ?: return false
    val button = generateSequence(close) { it.parent }.firstOrNull { it.isClickable } ?: return false
    automation.takeScreenshot()?.let { saveDeviceScreenshot(context, "emulator-launcher-anr", it) }
    android.util.Log.w("QuotaDeviceEnvironment", "Closing known launcher ANR on disposable emulator: ${launcher.packageName}; screenshot retained")
    check(button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Cannot close emulator launcher dialog" }
    return true
}
