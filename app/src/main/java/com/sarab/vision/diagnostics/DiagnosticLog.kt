package com.sarab.vision.diagnostics

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Process
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.PrintWriter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * App diagnostics only: no logcat capture, device identifiers, GPS, network
 * names or camera pixels. Each entry is capped before entering a bounded
 * queue; camera/IMU callers never wait for storage. Three private files retain
 * at most 1.5 MiB across process restarts.
 */
object DiagnosticLog {
    private const val MAX_FILE_BYTES = 512 * 1024
    private const val MAX_ENTRY_CHARS = 8 * 1024
    private val namesOldestFirst = listOf("events.2.log", "events.1.log", "events.log")
    private val componentCharacters = Regex("[^a-zA-Z0-9_.-]")
    private val tasks = ArrayBlockingQueue<Runnable>(256)
    private val droppedEntries = AtomicLong()
    private val storageLock = Any()
    @Volatile private var filesRoot: File? = null
    @Volatile private var appContext: Context? = null

    // A single process-scoped daemon orders appends and export barriers. Unlike
    // an unbounded executor, a burst of decoder errors cannot exhaust memory.
    @Suppress("unused")
    private val worker = Thread({
        while (true) {
            try {
                tasks.take().run()
            } catch (_: Throwable) {
                // Failure to write diagnostics must never stop navigation or
                // kill the worker needed by a later export attempt.
            }
        }
    }, "Sarab-Diagnostics").apply {
        isDaemon = true
        start()
    }

    /** Call once at app entry; repeated Activity creation keeps the same log owner. */
    @Synchronized
    fun initialize(context: Context) {
        if (appContext != null) return
        try {
            val application = context.applicationContext ?: context
            filesRoot = application.filesDir
            appContext = application
            record("SESSION", "Process started\n${deviceHeader(application)}")
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
                try {
                    // Crash persistence bypasses the queue: Android's default
                    // handler terminates the process before a queued task runs.
                    val stack = BoundedStackWriter()
                    failure.printStackTrace(PrintWriter(stack))
                    val entry = formatEntry("CRASH", "Thread: ${thread.name}\n$stack")
                    synchronized(storageLock) { append(entry, durable = true) }
                } catch (_: Throwable) {
                    // The original crash must still reach Android even if the
                    // filesystem is full or allocation already failed.
                } finally {
                    if (previous != null) {
                        previous.uncaughtException(thread, failure)
                    } else {
                        Process.killProcess(Process.myPid())
                        kotlin.system.exitProcess(10)
                    }
                }
            }
        } catch (_: Throwable) {
            // Diagnostics are optional; an inaccessible files directory must
            // never prevent the camera screen from opening.
        }
    }

    fun record(component: String, message: String) {
        if (appContext == null) return
        try {
            val safeComponent = component.take(40).replace(componentCharacters, "_")
            val safeMessage = cap(message)
            val time = System.currentTimeMillis()
            if (!tasks.offer(Runnable {
                    synchronized(storageLock) {
                        val dropped = droppedEntries.getAndSet(0)
                        if (dropped > 0) append(formatEntry("LOGGER", "$dropped entries dropped while the queue was full"))
                        append(formatEntry(safeComponent, safeMessage, time))
                    }
                })) droppedEntries.incrementAndGet()
        } catch (_: Throwable) {
            // Includes allocation pressure during a camera failure burst.
        }
    }

    /**
     * Writes to the document selected by Android's CreateDocument picker.
     * A FIFO barrier snapshots all accepted preceding records before export;
     * subsequent frames cannot change the exported bytes. Null means success.
     */
    suspend fun writeTo(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            if (uri.scheme != ContentResolver.SCHEME_CONTENT) return@withContext "رابط ملف التشخيص غير صالح. اختر الملف من نافذة الحفظ."
            initialize(context)
            if (appContext == null) return@withContext "تعذّر الوصول إلى سجل التطبيق الداخلي."
            val snapshot = CompletableFuture<ByteArray>()
            val barrier = Runnable {
                try {
                    synchronized(storageLock) {
                        snapshot.complete(snapshotBytes(context))
                    }
                } catch (failure: Throwable) {
                    snapshot.completeExceptionally(failure)
                }
            }
            // Blocking is confined to Dispatchers.IO. A full queue must not
            // discard the export barrier or let it overtake the final errors.
            if (!tasks.offer(barrier, 10, TimeUnit.SECONDS)) {
                return@withContext "السجل مشغول حالياً. انتظر قليلاً ثم أعد تصديره."
            }
            val bytes = snapshot.get(15, TimeUnit.SECONDS)
            val stream = context.contentResolver.openOutputStream(uri, "wt")
                ?: return@withContext "تعذّر فتح الملف للحفظ. اختر مجلداً آخر وأعد المحاولة."
            stream.use {
                it.write(bytes)
                it.flush()
            }
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            "لم يُسمح بحفظ الملف في هذا المكان. اختر مجلداً آخر."
        } catch (_: Exception) {
            "تعذّر تصدير سجل التشخيص. تأكد من توفر مساحة واختر الملف مجدداً."
        }
    }

    private fun snapshotBytes(context: Context): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(("Sarab Vision diagnostic export\nExported UTC: ${timestamp()}\n" +
            deviceHeader(context) + "\nApp diagnostic events only; no camera images or location history.\n\n").toByteArray(Charsets.UTF_8))
        val dropped = droppedEntries.getAndSet(0)
        if (dropped > 0) append(formatEntry("LOGGER", "$dropped entries dropped while the queue was full"))
        for (name in namesOldestFirst) {
            val file = logFile(name)
            if (!file.exists()) continue
            output.write("\n--- $name ---\n".toByteArray(Charsets.UTF_8))
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var remaining = MAX_FILE_BYTES
                while (remaining > 0) {
                    val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    remaining -= count
                }
            }
        }
        return output.toByteArray()
    }

    private fun append(entry: String, durable: Boolean = false) {
        val bytes = entry.toByteArray(Charsets.UTF_8)
        val current = logFile("events.log")
        if (current.length() + bytes.size > MAX_FILE_BYTES) {
            val oldest = logFile("events.2.log")
            val previous = logFile("events.1.log")
            if (oldest.exists() && !oldest.delete()) throw IOException("Could not rotate oldest app log")
            if (previous.exists() && !previous.renameTo(oldest)) throw IOException("Could not rotate previous app log")
            if (current.exists() && !current.renameTo(previous)) throw IOException("Could not rotate current app log")
        }
        FileOutputStream(current, true).use {
            it.write(bytes)
            it.flush()
            if (durable) it.fd.sync()
        }
    }

    /** All persistence is restricted to these three names inside private app files. */
    private fun logFile(name: String): File {
        if (name !in namesOldestFirst) throw IOException("Invalid diagnostic log name")
        val root = filesRoot?.canonicalFile ?: throw IOException("Diagnostics not initialized")
        val directory = File(root, "diagnostics")
        if (directory.canonicalFile != directory.absoluteFile) throw IOException("Unexpected diagnostic directory link")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not create diagnostic directory")
        val file = File(directory, name)
        if (file.canonicalFile != file.absoluteFile) throw IOException("Unexpected diagnostic file link")
        return file
    }

    private fun formatEntry(component: String, message: String, time: Long = System.currentTimeMillis()): String =
        "${timestamp(time)} [$component] ${cap(message)}\n"

    private fun cap(message: String): String =
        if (message.length <= MAX_ENTRY_CHARS) message.replace('\u0000', ' ')
        else message.take(MAX_ENTRY_CHARS).replace('\u0000', ' ') + "\n[entry truncated]"

    /** A pathological cause chain must not allocate an unbounded crash string. */
    private class BoundedStackWriter : Writer() {
        private val text = StringBuilder()
        override fun write(buffer: CharArray, offset: Int, length: Int) {
            val remaining = MAX_ENTRY_CHARS - text.length
            if (remaining > 0) text.append(buffer, offset, minOf(length, remaining))
        }
        override fun flush() = Unit
        override fun close() = Unit
        override fun toString(): String = text.toString()
    }

    private fun timestamp(time: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(time))

    @Suppress("DEPRECATION")
    private fun deviceHeader(context: Context): String {
        val version = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            "${info.versionName ?: "unknown"} ($code)"
        }.getOrDefault("unknown")
        return "App: ${context.packageName} $version\n" +
            "Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n" +
            "Device: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}\n"
    }
}
