package com.tsonglew.quodex

import android.app.assist.AssistStructure
import android.os.CancellationSignal
import android.service.autofill.*
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import java.util.concurrent.LinkedBlockingQueue

/** Debug-only password manager. Requests and values are synthetic and never reach a server. */
class FixtureAutofillService : AutofillService() {
    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        val structure = request.fillContexts.last().structure
        val fields = mutableListOf<AssistStructure.ViewNode>()
        fun visit(node: AssistStructure.ViewNode) {
            if (node.htmlInfo?.tag.equals("input", ignoreCase = true)) fields += node
            repeat(node.childCount) { visit(node.getChildAt(it)) }
        }
        repeat(structure.windowNodeCount) { visit(structure.getWindowNodeAt(it).rootViewNode) }
        requests.offer(fields)
        val email = fields.firstOrNull { it.htmlInfo?.attributes?.any { a -> a.first == "type" && a.second == "email" } == true }
        val password = fields.firstOrNull { it.htmlInfo?.attributes?.any { a -> a.first == "type" && a.second == "password" } == true }
        if (email?.autofillId == null || password?.autofillId == null) { callback.onSuccess(null); return }
        val label = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply {
            setTextViewText(android.R.id.text1, "Quodex synthetic saved account")
        }
        @Suppress("DEPRECATION")
        val dataset = Dataset.Builder(label)
            .setValue(email.autofillId!!, AutofillValue.forText("saved@example.test"))
            .setValue(password.autofillId!!, AutofillValue.forText("synthetic-password"))
            .build()
        callback.onSuccess(FillResponse.Builder().addDataset(dataset).build())
    }
    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) { callback.onSuccess() }
    companion object { val requests = LinkedBlockingQueue<List<AssistStructure.ViewNode>>() }
}
