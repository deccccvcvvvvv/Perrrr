package pro.cslauncher.core.download

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import pro.cslauncher.core.net.HttpStatusException
import pro.cslauncher.core.net.HttpTransport
import pro.cslauncher.core.util.*
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class DownloadTask(
    val url: String,
    val destination: Path,
    val integrity: Integrity = Integrity(),
    val label: String = destination.fileName.toString(),
    val mirrors: List<String> = emptyList(),
)

enum class TaskState { QUEUED, RUNNING, VERIFYING, DONE, SKIPPED, FAILED, CANCELLED }

data class TaskResult(val task: DownloadTask, val state: TaskState, val bytes: Long, val error: String? = null, val attempts: Int = 0)

data class BatchProgress(
    val batchName: String,
    val totalFiles: Int,
    val finishedFiles: Int,
    val failedFiles: Int,
    val totalBytes: Long,          // -1 when unknown
    val downloadedBytes: Long,
    val bytesPerSecond: Long,
    val currentFile: String?,
    val etaSeconds: Long?,         // null when no reasonable estimate exists
    val state: TaskState,
)

class BatchResult(val results: List<TaskResult>) {
    val failed get() = results.filter { it.state == TaskState.FAILED }
    val ok get() = failed.isEmpty() && results.none { it.state == TaskState.CANCELLED }
    fun summary(): String = if (ok) "${results.size} files ready" else
        "${failed.size} of ${results.size} files failed. First error: ${failed.firstOrNull()?.let { "${it.task.label}: ${it.error}" }}"
}

class DownloadFailedException(val result: BatchResult) : IOException(result.summary())

data class DownloadHistoryEntry(val batchName: String, val finishedAtMs: Long, val files: Int, val failed: Int, val bytes: Long, val firstError: String?)

/**
 * Shared download engine.
 * - bounded concurrency (mobile friendly), per-file retries with backoff, mirror fallback
 * - downloads into "<file>.part" and only moves into place after verification
 * - resumes with HTTP Range only when the server answers 206; otherwise restarts
 * - de-duplicates identical destinations across concurrent batches
 */
class DownloadManager(
    private val http: HttpTransport,
    @Volatile private var maxConcurrency: Int = 6,
    @Volatile private var maxAttempts: Int = 3,
    private val retryBaseDelayMs: Long = 600,
) {
    fun configure(concurrency: Int, attempts: Int) {
        require(concurrency in 1..16 && attempts in 1..6)
        maxConcurrency = concurrency; maxAttempts = attempts
    }
    private val inFlight = ConcurrentHashMap<Path, Deferred<TaskResult>>()
    private val _active = MutableStateFlow<Map<String, BatchProgress>>(emptyMap())
    val activeBatches: StateFlow<Map<String, BatchProgress>> = _active.asStateFlow()
    private val _history = MutableStateFlow<List<DownloadHistoryEntry>>(emptyList())
    val history: StateFlow<List<DownloadHistoryEntry>> = _history.asStateFlow()

    suspend fun downloadAll(batchName: String, tasks: List<DownloadTask>, onProgress: (BatchProgress) -> Unit = {}): BatchResult = coroutineScope {
        val unique = tasks.distinctBy { it.destination.toAbsolutePath().normalize() }
        checkDiskSpace(unique)
        val knownTotal = if (unique.all { (it.integrity.size ?: -1) >= 0 }) unique.sumOf { it.integrity.size!! } else -1L
        val done = AtomicInteger(); val failed = AtomicInteger(); val bytes = AtomicLong()
        val current = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val start = System.nanoTime()
        val sem = Semaphore(maxConcurrency)

        fun snapshot(state: TaskState): BatchProgress {
            val elapsed = (System.nanoTime() - start) / 1e9
            val bps = if (elapsed > 0.5) (bytes.get() / elapsed).toLong() else 0
            val eta = if (knownTotal > 0 && bps > 0 && elapsed > 2) ((knownTotal - bytes.get()).coerceAtLeast(0) / bps) else null
            return BatchProgress(batchName, unique.size, done.get(), failed.get(), knownTotal, bytes.get(), bps, current.get(), eta, state)
        }
        val ticker = launch {
            while (isActive) { val s = snapshot(TaskState.RUNNING); _active.update { it + (batchName to s) }; onProgress(s); delay(250) }
        }
        try {
            val results = unique.map { task ->
                async {
                    sem.withPermit {
                        current.set(task.label)
                        val r = runDeduplicated(task) { n -> bytes.addAndGet(n) }
                        if (r.state == TaskState.FAILED) failed.incrementAndGet()
                        done.incrementAndGet()
                        r
                    }
                }
            }.awaitAll()
            val br = BatchResult(results)
            val fin = snapshot(if (br.ok) TaskState.DONE else TaskState.FAILED)
            onProgress(fin)
            _history.update { (listOf(DownloadHistoryEntry(batchName, System.currentTimeMillis(), results.size, br.failed.size, bytes.get(), br.failed.firstOrNull()?.error)) + it).take(50) }
            br
        } finally {
            ticker.cancel()
            _active.update { it - batchName }
        }
    }

    private suspend fun runDeduplicated(task: DownloadTask, onBytes: (Long) -> Unit): TaskResult = coroutineScope {
        val key = task.destination.toAbsolutePath().normalize()
        val mine = async(start = CoroutineStart.LAZY) { downloadOne(task, onBytes) }
        val existing = inFlight.putIfAbsent(key, mine)
        if (existing != null) { mine.cancel(); return@coroutineScope existing.await().copy(task = task) }
        try { mine.await() } finally { inFlight.remove(key, mine) }
    }

    private fun checkDiskSpace(tasks: List<DownloadTask>) {
        val need = tasks.sumOf { maxOf(0L, it.integrity.size ?: 0L) }
        if (need == 0L || tasks.isEmpty()) return
        val dir = tasks.first().destination.toAbsolutePath().parent
        Files.createDirectories(dir)
        val free = pro.cslauncher.core.util.FsUtil.usableSpace(dir).takeIf { it > 0 } ?: Long.MAX_VALUE
        if (free < need + 64L * 1024 * 1024) throw InsufficientStorageException(need, free)
    }

    /** Downloads (or validates an existing copy of) a single file. Never leaves an unverified file at the destination. */
    suspend fun downloadOne(task: DownloadTask, onBytes: (Long) -> Unit = {}): TaskResult = withContext(Dispatchers.IO) {
        val dest = task.destination
        // Already present and valid -> skip.
        if (Files.isRegularFile(dest) && (task.integrity.hasChecksum || task.integrity.size != null) && task.integrity.check(dest) == null) {
            onBytes(Files.size(dest)); return@withContext TaskResult(task, TaskState.SKIPPED, Files.size(dest))
        }
        Files.createDirectories(dest.toAbsolutePath().parent)
        val part = dest.resolveSibling(dest.fileName.toString() + ".part")
        var lastError: String? = null
        val urls = listOf(task.url) + task.mirrors
        var attempt = 0
        while (attempt < maxAttempts * urls.size) {
            ensureActive()
            val url = urls[attempt % urls.size]
            attempt++
            var counted = 0L
            try {
                val resumeFrom = if (Files.isRegularFile(part)) Files.size(part) else 0L
                val headers = if (resumeFrom > 0) mapOf("Range" to "bytes=$resumeFrom-") else emptyMap()
                http.open(url, headers).use { resp ->
                    val append = when (resp.code) {
                        206 -> resumeFrom > 0
                        200 -> false
                        416 -> { Files.deleteIfExists(part); throw IOException("Server rejected resume range; restarting") }
                        else -> throw HttpStatusException(url, resp.code, "unexpected status")
                    }
                    val opts = if (append) arrayOf(StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)
                               else arrayOf(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
                    if (append) { onBytes(resumeFrom); counted += resumeFrom }
                    var received = 0L
                    Files.newOutputStream(part, *opts).use { out ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            ensureActive()
                            val n = resp.body.read(buf); if (n < 0) break
                            out.write(buf, 0, n); onBytes(n.toLong()); counted += n; received += n
                        }
                    }
                    // HttpURLConnection reports a silently closed fixed-length body as a normal EOF. Treat a short body as
                    // an interrupted transfer: keep the .part file so the next attempt resumes with a Range request.
                    val declared = resp.contentLength
                    if (declared >= 0 && received < declared) throw IOException("Connection closed after $received of $declared bytes")
                }
                val problem = task.integrity.check(part)
                if (problem != null) {
                    Files.deleteIfExists(part)
                    throw IOException("Verification failed: $problem")
                }
                Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING)
                return@withContext TaskResult(task, TaskState.DONE, Files.size(dest), attempts = attempt)
            } catch (e: CancellationException) {
                throw e  // keep the .part file so the next run can resume
            } catch (e: Exception) {
                onBytes(-counted)
                lastError = e.message ?: e.javaClass.simpleName
                val permanent = e is HttpStatusException && e.code in listOf(401, 403, 404, 410)
                if (e is HttpStatusException) Files.deleteIfExists(part)
                CsLog.w("Download", "Attempt $attempt for ${task.label} failed: $lastError")
                if (permanent && attempt % urls.size == 0) break
                if (e !is InterruptedIOException || attempt < maxAttempts) delay(retryBaseDelayMs * attempt)
            }
        }
        TaskResult(task, TaskState.FAILED, 0, lastError, attempt)
    }

    /** Removes stale partial downloads below [root]. Only "*.part" files are touched. */
    fun cleanPartialFiles(root: Path): Int {
        if (!Files.isDirectory(root)) return 0
        var n = 0
        Files.walk(root).use { s -> s.filter { it.fileName.toString().endsWith(".part") && Files.isRegularFile(it) }.forEach { Files.deleteIfExists(it); n++ } }
        return n
    }
}

class InsufficientStorageException(val requiredBytes: Long, val freeBytes: Long) :
    IOException("Not enough storage: need ${requiredBytes / 1048576} MB (+64 MB reserve), ${freeBytes / 1048576} MB free")
