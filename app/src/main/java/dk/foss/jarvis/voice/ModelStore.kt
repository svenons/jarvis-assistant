package dk.foss.jarvis.voice

import dk.foss.jarvis.net.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** State of a downloadable on-device model (speech recognition or synthesis). */
sealed interface ModelState {
    data object Missing : ModelState
    data class Downloading(val doneBytes: Long, val totalBytes: Long) : ModelState
    /** Downloaded and verified; unpacking an archive ([doneBytes] of the archive consumed so far). */
    data class Installing(val doneBytes: Long, val totalBytes: Long) : ModelState
    data object Ready : ModelState
    data class Failed(val message: String) : ModelState
}

/**
 * Tracks download state for a catalog of on-device models and runs their downloads. Downloads
 * run on an app-lifetime scope so leaving the Settings screen doesn't kill a big transfer, and
 * resume after a failure or cancel. Subclasses say where a model lives and how to install it.
 */
abstract class ModelStore<M : Any> {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val calls = ConcurrentHashMap<String, Call>()
    private val _states = MutableStateFlow<Map<String, ModelState>>(emptyMap())

    /** Download/ready state per model id. */
    val states: StateFlow<Map<String, ModelState>> = _states

    protected abstract fun idOf(model: M): String
    protected abstract fun totalBytes(model: M): Long
    protected abstract fun dir(model: M): File
    abstract fun isReady(model: M): Boolean

    /** Fetch (and unpack) everything the model needs. [progress] takes bytes downloaded so far. */
    protected abstract suspend fun install(model: M, progress: (Long) -> Unit)

    /** Free anything still holding the model's files open, before they're deleted. */
    protected open fun releaseFromMemory(model: M) {}

    /** Subclasses call this at the end of their `init`, once [isReady] can work. */
    protected fun initStates(models: List<M>) {
        _states.value = models.associate { idOf(it) to (if (isReady(it)) ModelState.Ready else ModelState.Missing) }
    }

    /** Start (or resume) a download. Main-thread only. */
    fun download(model: M) {
        val id = idOf(model)
        if (jobs[id]?.isActive == true || _states.value[id] is ModelState.Ready) return
        val total = totalBytes(model)
        set(model, ModelState.Downloading(0, total))
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val self = currentCoroutineContext()[Job]
            try {
                dir(model).mkdirs()
                install(model) { done -> set(model, ModelState.Downloading(done, total)) }
                if (jobs.remove(id, self)) set(model, ModelState.Ready)
            } catch (e: Throwable) {
                // If cancel() already removed this job it has set the state; don't overwrite a newer download.
                // Throwable, not Exception: an Error (out of memory, a missing class) must show up as Failed too.
                if (jobs.remove(id, self)) set(model, ModelState.Failed(e.message ?: e.javaClass.simpleName))
            }
        }
        jobs[id] = job
        job.start()
    }

    /** Stop a download; partial files stay so a later download resumes. */
    fun cancel(model: M) {
        val id = idOf(model)
        jobs.remove(id)?.cancel()
        calls.remove(id)?.cancel()
        if (_states.value[id].let { it is ModelState.Downloading || it is ModelState.Installing }) set(model, ModelState.Missing)
    }

    /** Delete the model's files and free its memory. */
    fun delete(model: M) {
        cancel(model)
        releaseFromMemory(model)
        dir(model).deleteRecursively()
        set(model, ModelState.Missing)
    }

    protected fun setInstalling(model: M, doneBytes: Long, totalBytes: Long) =
        set(model, ModelState.Installing(doneBytes, totalBytes))

    /**
     * Download [url] to [dest], resuming a `<dest>.part` from an earlier attempt, and only give it
     * its final name once its size and SHA-256 check out — so a file at the right size is verified.
     * [onBytes] gets the bytes of *this file* downloaded so far.
     */
    protected suspend fun fetch(model: M, url: String, dest: File, bytes: Long, sha256: String, onBytes: (Long) -> Unit) {
        if (dest.isFile && dest.length() == bytes) {
            onBytes(bytes)
            return
        }
        val tmp = File(dest.path + ".part")
        var have = if (tmp.isFile) tmp.length() else 0L
        if (have > bytes) { tmp.delete(); have = 0 }

        if (have < bytes) {
            val req = Request.Builder().url(url)
                .apply { if (have > 0) header("Range", "bytes=$have-") }
                .build()
            val call = Http.base.newCall(req).also { calls[idOf(model)] = it }
            call.execute().use { resp ->
                val resumed = resp.code == 206
                if (resp.code != 200 && !resumed) throw IOException("Download failed: HTTP ${resp.code} (${dest.name})")
                if (!resumed) have = 0 // server ignored Range → the body is the whole file
                val body = resp.body ?: throw IOException("Empty response (${dest.name})")
                FileOutputStream(tmp, resumed).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var written = have
                    var lastReport = written
                    body.byteStream().use { input ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            written += n
                            if (written - lastReport >= REPORT_EVERY_BYTES) {
                                lastReport = written
                                onBytes(written)
                            }
                        }
                    }
                }
            }
        }

        if (sha256Of(tmp) != sha256) {
            tmp.delete() // corrupt: resuming would only append to bad data
            throw IOException("Checksum mismatch (${dest.name}) — try downloading again")
        }
        if (!tmp.renameTo(dest)) throw IOException("Could not save ${dest.name}")
        onBytes(bytes)
    }

    /**
     * Unpack a `.tar.bz2` into [dest], dropping the archive's single top-level folder. bzip2 is
     * decoded in pure Java and is CPU-bound (~5 s per 67 MB on a fast desktop, several times that on
     * a phone), so [onProgress] reports how much of the archive has been consumed.
     */
    protected suspend fun unpack(archive: File, dest: File, onProgress: (Long) -> Unit) {
        dest.mkdirs()
        val base = dest.canonicalPath + File.separator
        val source = BufferedInputStream(CountingInput(archive.inputStream(), onProgress))
        TarArchiveInputStream(BZip2CompressorInputStream(source)).use { tar ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val entry = tar.nextEntry ?: break
                currentCoroutineContext().ensureActive()
                val rel = entry.name.substringAfter('/', "")
                if (rel.isEmpty()) continue
                val out = File(dest, rel).canonicalFile
                if (!out.path.startsWith(base)) throw IOException("Unsafe path in archive: ${entry.name}")
                if (entry.isDirectory) { out.mkdirs(); continue }
                if (!entry.isFile) continue // no symlinks or devices
                out.parentFile?.mkdirs()
                FileOutputStream(out).use { o ->
                    while (true) {
                        val n = tar.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                    }
                }
            }
        }
    }

    /** Counts bytes read from the archive file, reporting every ~512 KB. */
    private class CountingInput(input: InputStream, private val onBytes: (Long) -> Unit) : FilterInputStream(input) {
        private var count = 0L
        private var lastReport = 0L

        override fun read(): Int = super.read().also { if (it >= 0) add(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) add(it.toLong()) }

        private fun add(n: Long) {
            count += n
            if (count - lastReport >= 512 * 1024) { lastReport = count; onBytes(count) }
        }
    }

    private fun set(model: M, state: ModelState) = _states.update { it + (idOf(model) to state) }

    private fun sha256Of(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val REPORT_EVERY_BYTES = 512L * 1024
    }
}
