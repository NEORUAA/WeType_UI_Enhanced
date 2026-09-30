package com.xposed.wetypehook.wetype.settings

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.RandomAccessFile

/** Reads the host's preferences without another process's SharedPreferences memory cache. */
internal class HostPreferencesFile(private val file: File) {
    private val backup = File("${file.path}.bak")
    private val lockFile = File("${file.path}.lock")

    fun <T> withLock(block: () -> T): T = synchronized(this) {
        RandomAccessFile(lockFile, "rw").use { handle ->
            handle.channel.lock().use { block() }
        }
    }

    fun read(): Map<String, Any> = withLock {
        // SharedPreferences retains the backup until a write has completed successfully.
        val source = if (backup.exists()) backup else file
        if (!source.exists()) return@withLock emptyMap()
        source.inputStream().buffered().use { input ->
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(input, "UTF-8")
            check(parser.nextTag() == XmlPullParser.START_TAG && parser.name == "map")
            val values = mutableMapOf<String, Any>()
            var closedMap = false
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.END_TAG && parser.depth == 1 && parser.name == "map") {
                    closedMap = true
                }
                if (parser.eventType != XmlPullParser.START_TAG || parser.depth != 2) continue
                val name = parser.getAttributeValue(null, "name") ?: continue
                val value = parser.getAttributeValue(null, "value")
                // Appearance settings only use these scalar types; ignore unrelated entries.
                when (parser.name) {
                    "int" -> values[name] = requireNotNull(value).toInt()
                    "float" -> values[name] = requireNotNull(value).toFloat()
                    "boolean" -> values[name] = requireNotNull(value).toBooleanStrict()
                    // <string name="k">text</string> carries its value as element text,
                    // not as a value attribute, so it must be read with nextText().
                    "string" -> values[name] = parser.nextText()
                }
            }
            check(closedMap) { "Incomplete preferences XML" }
            values
        }
    }
}
