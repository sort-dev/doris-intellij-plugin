package dev.sort.doris.pipes

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.ActionWithDelegate
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.DynamicActionConfigurationCustomizer
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Wraps the four Execute actions. The platform calls [registerActions] synchronously after XML
 * registration at startup, and again on dynamic load; [unregisterActions] runs on dynamic unload.
 */
class DorisPipesActionConfiguration : DynamicActionConfigurationCustomizer {
    private val installed = LinkedHashMap<String, DorisPipesRunQueryAction>()

    override fun registerActions(actionManager: ActionManager) {
        if (installed.isNotEmpty()) return
        for ((index, id) in EXECUTE_IDS.withIndex()) {
            // Resolve stubs before replacement. The platform's base-action slot is NOT a chain.
            val previous = requireNotNull(actionManager.getAction(id)) { "Missing Execute action: $id" }
            val replacement = if (index == 3) DorisPipesRunSelectionAction(previous)
                else DorisPipesRunQueryAction(index + 1, previous)
            actionManager.replaceAction(id, replacement)
            installed[id] = replacement
        }
    }

    override fun unregisterActions(actionManager: ActionManager) {
        for ((id, replacement) in installed) {
            // A later peer captured this wrapper as its predecessor; its chain cannot be rewritten,
            // so leave the wrapper in place as a pure pass-through.
            replacement.detach()
            if (actionManager.getAction(id) === replacement) actionManager.replaceAction(id, replacement.delegate)
        }
        installed.clear()
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
        // The platform registers this promoter even when its implementation is package-private
        // (263+). Use the registered instance through the public ActionPromoter contract.
        val databasePromoter = ActionPromoter.EP_NAME.extensionList.firstOrNull {
            it.javaClass.name == "com.intellij.database.actions.DatabaseActionPromoter"
        } ?: return emptyList()
        return databasePromoter.promote(delegates, context).orEmpty().flatMap { promoted ->
            actions.indices.filter { delegates[it] === promoted }.map { actions[it] }
        }
    }
}
