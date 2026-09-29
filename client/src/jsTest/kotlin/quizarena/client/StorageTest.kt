package quizarena.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Device-local persistence. The backend is injected, so none of this touches
 * the browser's real localStorage.
 */
class StorageTest {
    private fun storage(backend: MemoryBackend = MemoryBackend()) = backend to Storage(backend)

    @Test
    fun prefsRoundTripAndMerge() {
        val (_, store) = storage()
        assertTrue(store.savePrefs(Prefs(theme = "dark")))
        assertEquals("dark", store.getPrefs().theme)

        store.updatePrefs { it.copy(lastConfig = StoredConfig(topics = listOf("alpha"))) }
        val prefs = store.getPrefs()
        assertEquals("dark", prefs.theme) // the patch did not clobber the theme
        assertEquals(listOf("alpha"), prefs.lastConfig?.topics)
    }

    @Test
    fun valuesAreWrappedInAVersionedNamespacedEnvelope() {
        val (backend, store) = storage()
        store.savePrefs(Prefs(theme = "light"))
        val raw = backend.dump()

        assertTrue(raw.keys.all { it.startsWith("quizapp:") }, "keys are namespaced: ${raw.keys}")
        val stored = raw.getValue("quizapp:prefs")
        assertTrue(stored.contains("\"v\":${Config.STORAGE_SCHEMA_VERSION}"), stored)
    }

    @Test
    fun playersAreRememberedPerDeviceCaseInsensitively() {
        val (_, store) = storage()
        store.rememberPlayer(StoredPlayer("Ada", "key-1"))

        assertEquals("Ada", store.getCurrentPlayer()?.name)
        assertEquals("key-1", store.findKnownPlayer("ADA")?.key)
        assertNull(store.findKnownPlayer("Grace"))

        // Re-claiming the same name replaces the entry rather than duplicating it.
        store.rememberPlayer(StoredPlayer("ada", "key-2"))
        assertEquals("key-2", store.findKnownPlayer("Ada")?.key)
    }

    @Test
    fun forgettingAPlayerClearsThemAndTheCurrentPointer() {
        val (_, store) = storage()
        store.rememberPlayer(StoredPlayer("Ada", "key-1"))
        store.forgetPlayer("ADA")

        assertNull(store.getCurrentPlayer())
        assertNull(store.findKnownPlayer("Ada"))
    }

    @Test
    fun activeAttemptIdSaveLoadAndClear() {
        val (_, store) = storage()
        assertNull(store.getActiveAttemptId())

        store.setActiveAttemptId("abc123xyz")
        assertEquals("abc123xyz", store.getActiveAttemptId())

        store.clearActiveAttemptId()
        assertNull(store.getActiveAttemptId())
    }

    @Test
    fun corruptJsonAndOldSchemaVersionsFallBackSafely() {
        val corrupt = MemoryBackend(mapOf("quizapp:prefs" to "{not json"))
        assertEquals(Prefs(), Storage(corrupt).getPrefs())

        val stale = MemoryBackend(mapOf("quizapp:prefs" to """{"v":1,"data":{"theme":"dark"}}"""))
        val store = Storage(stale)
        assertNull(store.getPrefs().theme) // discarded, not trusted
        assertTrue("quizapp:prefs" !in stale.dump(), "the stale entry is removed")
    }

    @Test
    fun aThrowingBackendNeverCrashesTheApp() {
        val hostile = object : StorageBackend {
            override fun getItem(key: String): String? = throw RuntimeException("disabled")
            override fun setItem(key: String, value: String) = throw RuntimeException("quota")
            override fun removeItem(key: String) = throw RuntimeException("disabled")
        }
        val errors = mutableListOf<Throwable>()
        val store = Storage(hostile, onError = { errors.add(it) })

        assertEquals(Prefs(), store.getPrefs()) // falls back
        assertTrue(!store.savePrefs(Prefs(theme = "dark"))) // reports failure rather than throwing
        assertNull(store.getCurrentPlayer())
        assertTrue(errors.isNotEmpty())
    }
}
