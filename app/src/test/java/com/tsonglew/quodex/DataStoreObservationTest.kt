package com.tsonglew.quodex

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreObservationTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun collectorStartedDuringSaveReceivesTheCommittedSetting() = runTest {
        val writeStarted = CompletableDeferred<Unit>()
        val finishWrite = CompletableDeferred<Unit>()
        val serializer = object : Serializer<Int> {
            override val defaultValue = 0
            override suspend fun readFrom(input: InputStream) = input.read().coerceAtLeast(0)
            override suspend fun writeTo(t: Int, output: OutputStream) {
                if (t == 2) {
                    writeStarted.complete(Unit)
                    finishWrite.await()
                }
                output.write(t)
            }
        }
        val storeScope = CoroutineScope(coroutineContext + SupervisorJob())
        val store = DataStoreFactory.create(serializer, scope = storeScope) {
            files.newFile("widget-setting")
        }
        try {
            store.updateData { 1 }
            // A newly bound widget can start observing while the settings screen saves.
            // Hold the write before its atomic file replacement, with no sleeps or polling.
            val writer = async { store.updateData { 2 } }
            writeStarted.await()
            val observed = Channel<Int>(Channel.UNLIMITED)
            val collector = launch { store.data.collect { observed.send(it) } }
            try {
                assertEquals(1, withTimeout(5_000) { observed.receive() })
                finishWrite.complete(Unit)
                writer.await()
                assertEquals("A new observer must receive the completed save", 2,
                    withTimeoutOrNull(5_000) { observed.receive() })
            } finally {
                collector.cancel()
                observed.close()
            }
        } finally {
            finishWrite.complete(Unit)
            storeScope.cancel()
        }
    }
}
