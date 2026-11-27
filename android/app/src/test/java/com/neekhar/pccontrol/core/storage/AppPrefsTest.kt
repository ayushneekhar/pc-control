package com.neekhar.pccontrol.core.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPrefsTest {

    private class FakePreferencesDataStore(
        private val initialGate: CompletableDeferred<Preferences>? = null,
        initial: Preferences = emptyPreferences(),
    ) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)

        override val data: Flow<Preferences> = flow {
            if (initialGate != null) {
                state.value = initialGate.await()
            }
            state.collect { emit(it) }
        }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }

    @Test
    fun awaitLoaded_suspendsUntilFirstEmissionAndReturnsPersistedSnapshot() = runBlocking {
        val gate = CompletableDeferred<Preferences>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val prefs = AppPrefs(
                dataStore = FakePreferencesDataStore(initialGate = gate),
                scope = scope,
            )

            val pending = async { prefs.awaitLoaded() }
            delay(50L)
            assertFalse("awaitLoaded must wait for initial DataStore emission", pending.isCompleted)

            gate.complete(
                preferencesOf(
                    stringPreferencesKey("server_url") to "https://custom-host.ts.net/",
                    stringPreferencesKey("chat_token") to "secret-token",
                    stringPreferencesKey("push_token") to "fcm-persisted",
                ),
            )

            val snap = withTimeout(5_000L) { pending.await() }
            assertEquals("https://custom-host.ts.net", snap.serverUrl)
            assertEquals("secret-token", snap.chatToken)
            assertEquals("fcm-persisted", snap.pushToken)
            assertTrue(pending.isCompleted)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun awaitLoaded_returnsUpdatedSnapshotAfterMutations() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val prefs = AppPrefs(
                dataStore = FakePreferencesDataStore(
                    initial = preferencesOf(
                        stringPreferencesKey("server_url") to "https://initial.ts.net",
                    ),
                ),
                scope = scope,
            )

            val initial = withTimeout(5_000L) { prefs.awaitLoaded() }
            assertEquals("https://initial.ts.net", initial.serverUrl)

            prefs.setServerUrl("https://updated.ts.net///")
            prefs.setPushStatus(token = "fcm-updated", error = null)

            val afterUpdate = withTimeout(5_000L) { prefs.awaitLoaded() }
            assertEquals("https://updated.ts.net", afterUpdate.serverUrl)
            assertEquals("fcm-updated", afterUpdate.pushToken)
        } finally {
            scope.cancel()
        }
    }
}
