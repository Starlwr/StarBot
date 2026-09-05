package com.starlwr.bot.bilibili

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ImportRuntimeHints
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.cache.annotation.EnableCaching
import org.springframework.retry.annotation.EnableRetry
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.context.annotation.EnableAspectJAutoProxy
import com.starlwr.bot.core.StarBotCoreApplication
import com.starlwr.bot.core.plugin.StarBotPluginDependencyDownloader
import com.starlwr.bot.core.plugin.StarBotPluginLoader

/** Standalone distribution entry point. The regular artifact remains loadable as a StarBot plugin. */
@SpringBootApplication
@ComponentScan(basePackages = ["com.starlwr.bot"], excludeFilters = [
    ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = [
            StarBotCoreApplication::class,
            StarBotPluginLoader::class,
            StarBotPluginDependencyDownloader::class
        ]
    )
])
@EnableAsync
@EnableRetry
@EnableCaching
@EnableScheduling
@EnableAspectJAutoProxy(exposeProxy = true)
@ImportRuntimeHints(StarBotNativeRuntimeHints::class)
class StarBotBilibiliApplication

fun main(args: Array<String>) {
    if (System.getProperty("java.home").isNullOrBlank()) {
        val executable = ProcessHandle.current().info().command().orElse(null)
        val runtimeHome = executable?.let { java.nio.file.Path.of(it).toAbsolutePath().parent }
            ?: java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath()
        System.setProperty("java.home", runtimeHome.toString())
    }
    runApplication<StarBotBilibiliApplication>(*args)
}
