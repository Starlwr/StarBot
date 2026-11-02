package com.starlwr.bot.bilibili

import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar

class StarBotNativeRuntimeHints : RuntimeHintsRegistrar {
    override fun registerHints(hints: RuntimeHints, classLoader: ClassLoader?) {
        // StarBot Core's plugin registry reparses configuration metadata even when no external
        // plugins are present. Native images therefore need the corresponding class resources.
        hints.resources().registerPattern("org/springframework/**/*.class")
        hints.resources().registerPattern("com/starlwr/bot/**/*.class")
    }
}
