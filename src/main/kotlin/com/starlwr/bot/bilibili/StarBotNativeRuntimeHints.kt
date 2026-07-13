package com.starlwr.bot.bilibili

import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar
import org.springframework.orm.jpa.support.PersistenceAnnotationBeanPostProcessor

class StarBotNativeRuntimeHints : RuntimeHintsRegistrar {
    override fun registerHints(hints: RuntimeHints, classLoader: ClassLoader?) {
        hints.reflection().registerType(PersistenceAnnotationBeanPostProcessor::class.java,
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS, MemberCategory.INVOKE_PUBLIC_METHODS)
        // StarBot Core's plugin registry reparses configuration metadata even when no external
        // plugins are present. Native images therefore need the corresponding class resources.
        hints.resources().registerPattern("org/springframework/**/*.class")
        hints.resources().registerPattern("com/starlwr/bot/**/*.class")
    }
}
