package com.starlwr.bot.bilibili.report

import com.starlwr.bot.core.plugin.StarBotComponent
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import java.nio.file.Files
import java.nio.file.Path

@ConfigurationProperties("starbot.bilibili.live-report.storage")
class LiveReportStorageProperties {
    var type: String = "sqlite"
    var redisUri: String = "redis://localhost:6379/0"
    var redisPrefix: String = "starbot:report:v1"
    var jdbcUrl: String? = null
    var username: String? = null
    var password: String? = null
    var sqliteFile: String? = null
    var failFast: Boolean = false
    var migrateLegacy: Boolean = true
    var communityJsonl: String? = null
    var v2RedisUri: String = "redis://localhost:6379/0"
    var bufferCapacity: Int = 20_000
    var batchSize: Int = 500
    var flushMillis: Long = 1_000
}

@StarBotComponent
@EnableConfigurationProperties(LiveReportStorageProperties::class)
class LiveReportStorageConfig(private val environment: Environment) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean(destroyMethod = "close")
    fun liveReportDataDriver(properties: LiveReportStorageProperties): LiveReportDataDriver {
        val configured = when (properties.type.lowercase()) {
            "redis" -> RedisLiveReportDataDriver(properties.redisUri, properties.redisPrefix)
            "mysql" -> JdbcLiveReportDataDriver("mysql",
                requireNotNull(properties.jdbcUrl) { "jdbc-url is required for MySQL report storage" },
                properties.username, properties.password)
            "sqlite" -> {
                val path = properties.sqliteFile?.let(Path::of) ?: defaultSqlitePath()
                Files.createDirectories(path.toAbsolutePath().parent)
                JdbcLiveReportDataDriver("sqlite", "jdbc:sqlite:${path.toAbsolutePath()}")
            }
            "memory" -> InMemoryLiveReportDataDriver()
            else -> error("Unsupported live report storage type: ${properties.type}")
        }
        return try {
            configured.initialize()
            if (configured is InMemoryLiveReportDataDriver) configured else BufferedLiveReportDataDriver(configured,
                properties.bufferCapacity, properties.batchSize, properties.flushMillis)
        }
        catch (e: Exception) {
            configured.close()
            if (properties.failFast) throw e
            log.error("直播报告存储 {} 初始化失败，临时降级到有界进程内存；恢复前不会写入持久层", properties.type, e)
            InMemoryLiveReportDataDriver().also { it.initialize() }
        }
    }

    private fun defaultSqlitePath(): Path {
        val explicit = environment.getProperty("spring.config.location")?.split(',')?.firstOrNull()
            ?: environment.getProperty("spring.config.additional-location")?.split(',')?.firstOrNull()
            ?: System.getProperty("spring.config.location")?.split(',')?.firstOrNull()
        val configPath = explicit?.removePrefix("optional:")?.removePrefix("file:")?.let(Path::of)
        val directory = configPath?.let { if (Files.isDirectory(it)) it else it.toAbsolutePath().parent }
            ?: Path.of(System.getProperty("user.dir"), "config")
        return directory.resolve("starbot-live-report.sqlite3")
    }
}
