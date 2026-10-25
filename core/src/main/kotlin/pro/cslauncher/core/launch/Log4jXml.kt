package pro.cslauncher.core.launch

/**
 * Mojang's logging configurations (assets/log_configs/client-*.xml) make the game print log4j XML events meant for
 * launchers. This streaming converter turns them into readable "[HH:mm:ss] [thread/LEVEL]: message" lines and passes
 * non-XML output (stdout prints, JVM messages) through unchanged. Feed it one line at a time; it keeps no unbounded state.
 */
class Log4jXml {
    private var level = "INFO"; private var thread = "main"; private var time = ""
    private var inThrowable = false; private var inMessage = false

    /** Returns zero or more readable lines for one raw output line. */
    fun feed(raw: String): List<String> {
        val line = raw.trimEnd()
        val t = line.trim()
        if (inThrowable || inMessage) {
            val endTag = if (inThrowable) "]]></log4j:Throwable>" else "]]></log4j:Message>"
            return if (t.endsWith(endTag)) { inThrowable = false; inMessage = false; listOfNotNull(line.removeSuffix(endTag).takeIf { it.isNotBlank() }) } else listOf(line)
        }
        if (t.startsWith("<log4j:Event ")) {
            level = attr(t, "level") ?: "INFO"; thread = attr(t, "thread") ?: "main"
            time = attr(t, "timestamp")?.toLongOrNull()?.let { fmt(it) } ?: ""
            return emptyList()
        }
        if (t == "</log4j:Event>") return emptyList()
        if (t.startsWith("<log4j:Message><![CDATA[")) {
            val body = t.removePrefix("<log4j:Message><![CDATA[")
            val prefix = (if (time.isNotEmpty()) "[$time] " else "") + "[$thread/$level]: "
            return if (body.endsWith("]]></log4j:Message>")) listOf(prefix + body.removeSuffix("]]></log4j:Message>"))
                   else { inMessage = true; listOf(prefix + body) }
        }
        if (t.startsWith("<log4j:Throwable><![CDATA[")) {
            val body = t.removePrefix("<log4j:Throwable><![CDATA[")
            return if (body.endsWith("]]></log4j:Throwable>")) listOf(body.removeSuffix("]]></log4j:Throwable>")) else { inThrowable = true; listOf(body) }
        }
        return listOf(line)
    }

    private fun attr(tag: String, name: String): String? = Regex("\\b$name=\"([^\"]*)\"").find(tag)?.groupValues?.get(1)
    private fun fmt(ms: Long): String = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withNano(0).toString()

    companion object {
        /** Stateless best-effort flattening of a single line (used when analysing stored logs line by line). */
        fun flattenText(line: String): String = line.trim().let { t ->
            when {
                t.startsWith("<log4j:Message><![CDATA[") -> t.removePrefix("<log4j:Message><![CDATA[").removeSuffix("]]></log4j:Message>")
                t.startsWith("<log4j:Throwable><![CDATA[") -> t.removePrefix("<log4j:Throwable><![CDATA[").removeSuffix("]]></log4j:Throwable>")
                else -> line
            }
        }
    }
}
