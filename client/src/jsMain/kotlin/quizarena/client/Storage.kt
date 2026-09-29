package quizarena.client

import kotlinx.browser.window
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Browser-side persistence for things that belong to this device only.
 * Quiz content, attempts, scores and history live in the server's database.
 *
 * Keys are namespaced and every value is wrapped in a versioned envelope
 * `{ v: <schema version>, data: <payload> }`. Corrupt or out-of-date entries are
 * discarded instead of crashing the app. The backend is injectable, so tests
 * (and browsers with storage disabled) run on an in-memory map.
 *
 * Keys
 *   quizapp:prefs          last used quiz setup + theme
 *   quizapp:players        names claimed on this device -> secret player keys
 *   quizapp:activeAttempt  id of the unfinished quiz, to offer "resume"
 */
interface StorageBackend {
    fun getItem(key: String): String?
    fun setItem(key: String, value: String)
    fun removeItem(key: String)
}

class MemoryBackend(initial: Map<String, String> = emptyMap()) : StorageBackend {
    private val map = initial.toMutableMap()
    override fun getItem(key: String): String? = map[key]
    override fun setItem(key: String, value: String) {
        map[key] = value
    }

    override fun removeItem(key: String) {
        map.remove(key)
    }

    fun dump(): Map<String, String> = map.toMap()
}

private class LocalStorageBackend : StorageBackend {
    override fun getItem(key: String): String? = window.localStorage.getItem(key)
    override fun setItem(key: String, value: String) = window.localStorage.setItem(key, value)
    override fun removeItem(key: String) = window.localStorage.removeItem(key)
}

data class ResolvedBackend(val backend: StorageBackend, val persistent: Boolean)

/**
 * Returns localStorage when it is actually usable (it can throw in private
 * mode, sandboxed iframes or when disabled), otherwise a memory backend.
 */
fun resolveBackend(): ResolvedBackend = try {
    val storage = LocalStorageBackend()
    val probe = "${Config.STORAGE_NAMESPACE}:probe"
    storage.setItem(probe, "1")
    storage.removeItem(probe)
    ResolvedBackend(storage, persistent = true)
} catch (t: Throwable) {
    ResolvedBackend(MemoryBackend(), persistent = false)
}

@Serializable
data class StoredPlayer(val name: String, val key: String)

@Serializable
data class StoredPlayers(val current: String? = null, val known: List<StoredPlayer> = emptyList())

@Serializable
data class Prefs(
    val theme: String? = null,
    val lastConfig: StoredConfig? = null,
)

@Serializable
data class StoredConfig(
    val topics: List<String> = emptyList(),
    val difficulty: String = "mixed",
    val count: Int = 10,
    val timerMode: String = TimerModes.QUESTION,
    val secondsPerQuestion: Int = 30,
    val shuffle: Boolean = true,
    val negativeMarking: Boolean = false,
)

@Serializable
private data class Envelope<T>(val v: Int, val data: T)

class Storage(
    private val backend: StorageBackend = MemoryBackend(),
    private val namespace: String = Config.STORAGE_NAMESPACE,
    private val onError: (Throwable) -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun key(name: String) = "$namespace:$name"

    private fun <T> read(
        name: String,
        fallback: T,
        deserializer: kotlinx.serialization.KSerializer<T>,
    ): T = try {
        val raw = backend.getItem(key(name))
        if (raw == null) {
            fallback
        } else {
            val envelope = json.decodeFromString(Envelope.serializer(deserializer), raw)
            if (envelope.v != Config.STORAGE_SCHEMA_VERSION) {
                backend.removeItem(key(name))
                fallback
            } else {
                envelope.data
            }
        }
    } catch (t: Throwable) {
        onError(t)
        runCatching { backend.removeItem(key(name)) }
        fallback
    }

    private fun <T> write(
        name: String,
        data: T,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): Boolean = try {
        val payload = json.encodeToString(
            Envelope.serializer(serializer),
            Envelope(Config.STORAGE_SCHEMA_VERSION, data),
        )
        backend.setItem(key(name), payload)
        true
    } catch (t: Throwable) {
        // Quota exceeded or storage disabled: the app keeps working in memory.
        onError(t)
        false
    }

    private fun remove(name: String) {
        try {
            backend.removeItem(key(name))
        } catch (t: Throwable) {
            onError(t)
        }
    }

    // ----------------------------------------------------------- preferences

    fun getPrefs(): Prefs = read("prefs", Prefs(), Prefs.serializer())

    fun savePrefs(prefs: Prefs): Boolean = write("prefs", prefs, Prefs.serializer())

    fun updatePrefs(patch: (Prefs) -> Prefs): Boolean = savePrefs(patch(getPrefs()))

    // --------------------------------------------------------------- players

    private fun readPlayers(): StoredPlayers =
        read("players", StoredPlayers(), StoredPlayers.serializer())

    private fun sameName(a: String, b: String) = a.equals(b, ignoreCase = true)

    /** The player currently playing on this device, or null. */
    fun getCurrentPlayer(): StoredPlayer? {
        val players = readPlayers()
        val current = players.current ?: return null
        return players.known.firstOrNull { sameName(it.name, current) }
    }

    /** A name this device already owns (case-insensitive), or null. */
    fun findKnownPlayer(name: String): StoredPlayer? =
        readPlayers().known.firstOrNull { sameName(it.name, name) }

    /** Remembers a player and makes them current. */
    fun rememberPlayer(player: StoredPlayer): Boolean {
        val known = readPlayers().known.filterNot { sameName(it.name, player.name) }
        return write("players", StoredPlayers(player.name, known + player), StoredPlayers.serializer())
    }

    fun setCurrentPlayer(name: String?): Boolean =
        write("players", readPlayers().copy(current = name), StoredPlayers.serializer())

    /** Drops a player whose key the server no longer recognises (e.g. a database reset). */
    fun forgetPlayer(name: String): Boolean {
        val players = readPlayers()
        return write(
            "players",
            StoredPlayers(
                current = players.current?.takeUnless { sameName(it, name) },
                known = players.known.filterNot { sameName(it.name, name) },
            ),
            StoredPlayers.serializer(),
        )
    }

    // --------------------------------------------------------- active attempt

    fun getActiveAttemptId(): String? =
        read("activeAttempt", null, String.serializer().nullable)

    fun setActiveAttemptId(id: String): Boolean =
        write("activeAttempt", id, String.serializer())

    fun clearActiveAttemptId() = remove("activeAttempt")
}
