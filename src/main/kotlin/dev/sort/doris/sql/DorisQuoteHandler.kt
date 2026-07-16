package dev.sort.doris.sql

/**
 * Doris string literals are MULTI-LINE (both `'…'` and `"…"`, e.g. TVF property values spanning
 * several lines) — pressing Enter inside one must insert a plain newline.
 *
 * Without this registration it didn't: the platform's `EnterInStringLiteralHandler` splits the
 * string at the caret into a `'…' ||  '…'` concatenation whenever the file language's quote
 * handler implements [com.intellij.codeInsight.editorActions.JavaLikeQuoteHandler]. The base
 * `SQL` language registers exactly such a handler (`SqlQuoteHandler.Concat`, concatenation
 * operator `||`), and DorisSQL — having no registration of its own — inherited it through the
 * LanguageExtension base-language fallback. Notably JetBrains' own MySQL dialect opts out of the
 * splitting by registering the plain (non-Concat) [SqlQuoteHandler]; DorisSQL follows the same
 * pattern via this subclass. Quote auto-closing/typing behavior is unchanged — it all lives in
 * the inherited base class, the same one stock MySQL files use.
 *
 * Dogfood 2026-07-15: multi-line TVF property strings (`'query' = "select …␤ from …"`) were
 * un-typeable — every Enter injected the `' ||  '` split.
 */
class DorisQuoteHandler : com.intellij.database.sql.common.impl.editor.SqlQuoteHandler()
