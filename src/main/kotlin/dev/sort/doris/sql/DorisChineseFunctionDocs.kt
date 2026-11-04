package dev.sort.doris.sql

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Immutable, versioned documentation shipped with the plugin. Never accesses the network. */
internal object DorisChineseFunctionDocs {
    private const val ROOT = "/docs/functions/zh-CN/"
    private data class Entry(val page: String, val url: String)
    private val index: Map<String, Entry> by lazy {
        checkNotNull(javaClass.getResourceAsStream(ROOT + "index.tsv")).bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }.associate { line ->
                val fields = line.split('\t')
                check(fields.size == 3 && fields[1].matches(Regex("[a-zA-Z0-9_-]+")))
                check(fields[2].startsWith("https://doris.apache.org/zh-CN/docs/4.x/"))
                fields[0] to Entry(fields[1], fields[2])
            }
        }
    }
    private val pages = ConcurrentHashMap<String, String>()

    fun page(name: String): String? = index[name.uppercase(Locale.ROOT)]?.page
    fun names(): Set<String> = index.keys
    fun html(page: String): String = pages.computeIfAbsent(page) {
        checkNotNull(javaClass.getResourceAsStream(ROOT + "pages/$it.html")).bufferedReader().use { reader -> reader.readText() }
    }
    fun url(page: String): String = index.values.first { it.page == page }.url
}
