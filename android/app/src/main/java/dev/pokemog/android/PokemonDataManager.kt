package dev.pokemog.android

import android.content.Context
import android.app.ActivityManager
import android.util.AtomicFile
import android.annotation.SuppressLint
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PokemonDataStatus(val active: DataIdentity? = null, val message: String = "Using offline data")

internal object OverlayResultData {
    private val mutableIdentity = MutableStateFlow<DataIdentity?>(null)
    val identity = mutableIdentity.asStateFlow()
    fun update(value: DataIdentity?) { mutableIdentity.value = value }
}

/** Process-scoped publication. Network work never belongs to an Activity or capture session. */
class PokemonDataManager private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preferences by lazy { context.getSharedPreferences("pokemon-data-check", Context.MODE_PRIVATE) }
    private val mutableStatus = MutableStateFlow(PokemonDataStatus())
    val status = mutableStatus.asStateFlow()
    @Volatile private var repository: PokemonRepository? = null
    private val startupCheck = StartupDataCheck(scope) { checkForUpdates() }
    private val root by lazy { File(context.filesDir, "data") }
    private val pointer by lazy { AtomicFile(File(root, "current")) }
    private val store by lazy { DataPackStore(root, object : DataPointer {
        override fun read() = pointer.openRead().use { utf8(it.boundedBytes(128)) }
        override fun write(value: String) {
            val stream = pointer.startWrite()
            try { stream.write(value.toByteArray(Charsets.UTF_8)); pointer.finishWrite(stream) }
            catch (error: Exception) { pointer.failWrite(stream); throw error }
        }
    }) }

    @Synchronized fun snapshot(): PokemonRepository {
        repository?.let { return it }
        val lowRam = context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
        val cached = runCatching { store.load() }.getOrNull()
        val repo = cached?.takeIf { it.manifest.identity.version >= BUNDLED_DATA.version }
            ?.let { PokemonRepository(it, lowRam) } ?: PokemonRepository(context)
        repository = repo
        mutableStatus.value = PokemonDataStatus(repo.identity, when {
            preferences.getLong("failure", 0) > 0 -> "Check failed; using offline data"
            preferences.getLong("success", 0) > 0 -> "Up to date (last successful check)"
            else -> "Using offline data"
        })
        return repo
    }

    fun checkOnStartup() = startupCheck.trigger()

    @SuppressLint("ApplySharedPref") // Runs on IO; persist throttling before a request can leave the process.
    private fun checkForUpdates() {
        try {
            val now = System.currentTimeMillis()
            val savedSuccess = preferences.getLong("success", 0)
            val savedFailure = preferences.getLong("failure", 0)
            val success = normalizedDataCheckTimestamp(now, savedSuccess)
            val failure = normalizedDataCheckTimestamp(now, savedFailure)
            if (success != savedSuccess || failure != savedFailure) {
                check(preferences.edit().putLong("success", success).putLong("failure", failure).commit())
            }
            if (!dataCheckDue(now, success, failure)) return
            val current = snapshot()
            mutableStatus.value = PokemonDataStatus(current.identity, "Checking")
            // Do not request anything if persistent rate limiting cannot be recorded.
            check(preferences.edit().putLong("failure", System.currentTimeMillis()).commit())
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(90)
            val assets = DataPackNetwork.releaseAssets(DataPackNetwork.get(DATA_API, 1024 * 1024, deadline))
            val manifestBytes = DataPackNetwork.get(assets.getValue("manifest.json"), MANIFEST_LIMIT, deadline)
            val manifest = parseManifest(manifestBytes)
            require(assets.getValue("manifest.json").contains("/data-${manifest.identity.version}/"))
            if (manifest.identity.version > current.identity.version) {
                mutableStatus.value = PokemonDataStatus(current.identity, "Update available; validating")
                val files = manifest.files.mapValues { (name, entry) ->
                    DataPackNetwork.get(assets.getValue(name), entry.size, deadline).also { verifyFile(it, entry) }
                }
                check(System.nanoTime() < deadline) { "Data update deadline exceeded" }
                val pack = store.activate(manifestBytes, files, current.identity.version, deadline)
                val next = PokemonRepository(pack, context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true)
                repository = next
            }
            check(System.nanoTime() < deadline) { "Data update deadline exceeded" }
            preferences.edit().putLong("success", System.currentTimeMillis()).remove("failure").commit()
            mutableStatus.value = PokemonDataStatus(repository!!.identity, "Up to date")
        } catch (_: Exception) {
            runCatching { preferences.edit().putLong("failure", System.currentTimeMillis()).commit() }
            mutableStatus.value = PokemonDataStatus(repository?.identity, "Check failed; using offline data")
        } catch (_: OutOfMemoryError) {
            mutableStatus.value = PokemonDataStatus(repository?.identity, "Check failed; using offline data")
        }
    }

    companion object {
        @Volatile private var instance: PokemonDataManager? = null
        fun get(context: Context): PokemonDataManager = instance ?: synchronized(this) {
            instance ?: PokemonDataManager(context.applicationContext).also { instance = it }
        }
    }
}

internal val BUNDLED_DATA = DataIdentity(1788931909299L, "04ee0835e80f30c45376415725c0039cbdd12c5e", "2026-09-09T05:31:49.299Z")
internal fun normalizedDataCheckTimestamp(now: Long, time: Long): Long =
    if (time > now && (now < 0 || time - now > 5 * 60 * 1000L)) 0 else time

internal fun dataCheckDue(now: Long, success: Long, failure: Long): Boolean {
    fun recent(saved: Long, window: Long): Boolean {
        val time = normalizedDataCheckTimestamp(now, saved)
        return time > 0 && (now < time || now - time < window)
    }
    return !recent(success, 6 * 60 * 60 * 1000L) && !recent(failure, 15 * 60 * 1000L)
}
