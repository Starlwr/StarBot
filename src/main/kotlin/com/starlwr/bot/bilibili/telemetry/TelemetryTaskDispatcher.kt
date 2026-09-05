package com.starlwr.bot.bilibili.telemetry

import com.starlwr.bot.core.plugin.StarBotComponent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.time.Duration
import java.time.Instant
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@StarBotComponent
class TelemetryTaskDispatcher(
    @param:Qualifier("bilibiliTelemetryThreadPool") private val worker: ThreadPoolTaskExecutor,
    @param:Qualifier("bilibiliTelemetryScheduler") private val scheduler: ThreadPoolTaskScheduler,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val rejected = AtomicLong()
    private val overloadNoticeOpen = AtomicBoolean()

    fun submit(
        operation: String,
        roomId: Long? = null,
        onRejected: () -> Unit = {},
        action: () -> Unit,
    ): Boolean = try {
        scheduler.execute {
            try {
                worker.execute {
                    try {
                        action()
                    } catch (error: Throwable) {
                        log.warn("直播遥测任务执行失败: operation={}, room={}, reason={}", operation, roomId, error.toString())
                        log.debug("直播遥测任务失败详情: operation={}, room={}", operation, roomId, error)
                    }
                }
                if (overloadNoticeOpen.compareAndSet(true, false)) {
                    log.info("Bilibili 遥测执行器已恢复接收任务, 此前跳过 {} 个任务", rejected.getAndSet(0))
                }
            } catch (error: RejectedExecutionException) {
                val count = rejected.incrementAndGet()
                if (overloadNoticeOpen.compareAndSet(false, true)) {
                    log.warn("Bilibili 遥测执行器已满，将有界等待后仍无法提交的任务跳过；直播 WebSocket 不受影响: operation={}, room={}, skipped={}",
                        operation, roomId, count)
                } else {
                    log.debug("跳过过载遥测任务: operation={}, room={}, skipped={}, reason={}",
                        operation, roomId, count, error.toString())
                }
                runCatching(onRejected).onFailure {
                    log.debug("遥测拒绝回调失败: operation={}, room={}", operation, roomId, it)
                }
            }
        }
        true
    } catch (error: RejectedExecutionException) {
        log.debug("遥测调度器已关闭，忽略任务: operation={}, room={}", operation, roomId, error)
        false
    }

    fun schedule(
        delay: Duration,
        operation: String,
        roomId: Long? = null,
        onRejected: () -> Unit = {},
        action: () -> Unit,
    ): ScheduledFuture<*>? = runCatching {
        scheduler.schedule({ submit(operation, roomId, onRejected, action) }, Instant.now().plus(delay))
    }.getOrElse { error ->
        log.debug("遥测定时任务未建立: operation={}, room={}", operation, roomId, error)
        null
    }

    fun scheduleAtFixedRate(
        interval: Duration,
        operation: String,
        roomId: Long? = null,
        action: () -> Unit,
    ): ScheduledFuture<*> = scheduler.scheduleAtFixedRate(
        { submit(operation, roomId, action = action) }, interval)
}
