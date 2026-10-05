package dev.sort.doris.brikk;

import org.jetbrains.kotlin.idea.base.injection.KotlinLanguageInjectionContributorBaseKt;
import org.jetbrains.kotlin.idea.base.injection.TrimIndentHandler;
import org.jetbrains.kotlin.psi.KtStringTemplateExpression;

/**
 * Kotlin's contributor owns the indent handler. Its JVM accessor is public but the Kotlin
 * property is module-internal. Keep that dependency in this optional-integration bridge and
 * verify it on every supported IDEA generation rather than duplicating injection range logic.
 */
final class BrikkKotlinIndentBridge {
    private BrikkKotlinIndentBridge() {}

    static void useDefaultMargin(KtStringTemplateExpression host) {
        KotlinLanguageInjectionContributorBaseKt.setIndentHandler(host, new TrimIndentHandler("|"));
    }
}
