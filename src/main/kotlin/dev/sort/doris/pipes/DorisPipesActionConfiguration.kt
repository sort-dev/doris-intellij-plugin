package dev.sort.doris.pipes

import com.intellij.database.actions.DatabaseActionPromoter
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.ActionWithDelegate
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.ActionConfigurationCustomizer
import java.util.Collections
import java.util.IdentityHashMap

/** Startup-only registration: arbitrary removal from a captured action chain requires a restart. */
@Suppress("UnstableApiUsage")
class DorisPipesActionConfiguration : ActionConfigurationCustomizer,
    ActionConfigurationCustomizer.SyncHeavyCustomizeStrategy {
    private var installed = false

    override fun customize(actionManager: ActionManager) {
        if (installed) return
        for ((index, id) in EXECUTE_IDS.withIndex()) {
            // Resolve stubs before replacement. The platform's base-action slot is NOT a chain.
            val previous = requireNotNull(actionManager.getAction(id)) { "Missing Execute action: $id" }
            val replacement = if (index == 3) DorisPipesRunSelectionAction(previous)
                else DorisPipesRunQueryAction(index + 1, previous)
            actionManager.replaceAction(id, replacement)
        }
        installed = true
    }

    companion object {
        internal val EXECUTE_IDS = listOf(
            "Console.Jdbc.Execute", "Console.Jdbc.Execute.2", "Console.Jdbc.Execute.3",
            "Console.Jdbc.Execute.Selection",
        )
    }
}

/** 262's database promoter checks concrete class packages, not just RunQueryAction inheritance. */
class DorisPipesActionPromoter : ActionPromoter {
    override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction> {
        var containsDoris = false
        val delegates = actions.map { action ->
            var current = action
            var ours = current is DorisPipesRunQueryAction
            val seen = Collections.newSetFromMap(IdentityHashMap<AnAction, Boolean>())
            while (seen.add(current)) {
                val next = (current as? ActionWithDelegate<*>)?.delegate as? AnAction ?: break
                if (next in seen) break
                current = next
                ours = ours || current is DorisPipesRunQueryAction
            }
            containsDoris = containsDoris || ours
            if (ours) current else action
        }
        if (!containsDoris) return emptyList()
        return DatabaseActionPromoter().promote(delegates, context).orEmpty().flatMap { promoted ->
            actions.indices.filter { delegates[it] === promoted }.map { actions[it] }
        }
    }
}
