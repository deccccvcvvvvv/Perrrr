package pro.cslauncher.core.util

import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class LogRecord(val time: Instant, val level: LogLevel, val tag: String, val message: String, val error: Throwable?)

/** Structured, redacting logger. Platform sinks (logcat, files, stdout) register themselves. */
object CsLog {
    private val sinks = CopyOnWriteArrayList<(LogRecord) -> Unit>()
    @Volatile var minLevel = LogLevel.DEBUG

    fun addSink(sink: (LogRecord) -> Unit) { sinks += sink }
    fun removeSink(sink: (LogRecord) -> Unit) { sinks -= sink }

    fun log(level: LogLevel, tag: String, message: String, error: Throwable? = null) {
        if (level < minLevel) return
        val rec = LogRecord(Instant.now(), level, tag, Redactor.redact(message), error)
        sinks.forEach { runCatching { it(rec) } }
    }
    fun d(tag: String, msg: String) = log(LogLevel.DEBUG, tag, msg)
    fun i(tag: String, msg: String) = log(LogLevel.INFO, tag, msg)
    fun w(tag: String, msg: String, e: Throwable? = null) = log(LogLevel.WARN, tag, msg, e)
    fun e(tag: String, msg: String, e: Throwable? = null) = log(LogLevel.ERROR, tag, msg, e)
}

/** Removes credentials from any text that may be logged, exported or shown in a diagnostic report. */
object Redactor {
    private val patterns = listOf(
        Regex("(--accessToken\\s+)\\S+") to "$1<redacted>",
        Regex("(--session\\s+)\\S+") to "$1<redacted>",
        Regex("(?i)(\"?(access_?token|refresh_?token|id_token|password|x-api-key|Authorization)\"?\\s*[:=]\\s*\"?)(Bearer\\s+)?[^\"\\s,}]+") to "$1<redacted>",
        Regex("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}") to "<redacted-jwt>",
        Regex("(?i)(token:)[A-Za-z0-9._-]{16,}") to "$1<redacted>",
        Regex("(XBL3\\.0 x=)[^;\\s]+;[^\\s\"]+") to "$1<redacted>",
    )
    fun redact(text: String): String = patterns.fold(text) { acc, (re, rep) -> re.replace(acc, rep) }
    fun redactArgs(args: List<String>): List<String> {
        val out = ArrayList<String>(args.size)
        var hideNext = false
        for (a in args) {
            if (hideNext) { out += "<redacted>"; hideNext = false; continue }
            out += redact(a)
            if (a == "--accessToken" || a == "--session" || a == "--xuid" || a == "--clientId") hideNext = true
        }
        return out
    }
}
