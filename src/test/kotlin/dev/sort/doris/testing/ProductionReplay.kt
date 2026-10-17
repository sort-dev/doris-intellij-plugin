package dev.sort.doris.testing

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import dev.sort.doris.sql.DorisReplay

/**
 * Pins Doris statement replay to its production default (on) until [until] ends. The suite pins it off
 * globally for the flag-off golden corpus; user-facing contracts run the way the IDE does.
 */
fun productionReplay(until: Disposable) {
    val previous = System.getProperty(DorisReplay.PROPERTY)
    System.setProperty(DorisReplay.PROPERTY, "true")
    Disposer.register(until) {
        previous?.let { System.setProperty(DorisReplay.PROPERTY, it) } ?: System.clearProperty(DorisReplay.PROPERTY)
    }
}
