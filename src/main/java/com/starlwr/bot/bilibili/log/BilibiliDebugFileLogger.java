package com.starlwr.bot.bilibili.log;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.core.plugin.StarBotComponent;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Set;

/** Structured raw capture for replay-oriented DynamicDebug and LiveDebug files. */
@StarBotComponent
public class BilibiliDebugFileLogger {
    private static final Logger LOG = LoggerFactory.getLogger(BilibiliDebugFileLogger.class);
    private static final Logger DYNAMIC_LOG = LoggerFactory.getLogger("DynamicLogger");
    private static final Logger LIVE_LOG = LoggerFactory.getLogger("LiveMessageLogger");

    private final StarBotBilibiliProperties properties;
    private final DuplicateLogSuppressor fileDuplicates = new DuplicateLogSuppressor();

    public BilibiliDebugFileLogger(StarBotBilibiliProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void reportConfiguration() {
        StarBotBilibiliProperties.Network network = properties.getNetwork();
        LOG.info("Bilibili DEBUG 文件采集日志配置: categories={}, deduplicate={}, deduplicateSeconds={}",
                network.getFileCategories(), network.isFileDeduplicate(), fileWindowSeconds());
    }

    public void dynamic(String type, String dynamicId, Object payload) {
        write(DYNAMIC_LOG, "dynamic", type, "dynamicId", dynamicId, payload);
    }

    public void live(String type, Long roomId, Object payload) {
        write(LIVE_LOG, "live", type, "room", roomId == null ? "-" : roomId.toString(), payload);
    }

    private void write(Logger logger, String category, String type, String identityName,
                       String identity, Object payload) {
        if (!categoryEnabled(category, type) || !logger.isDebugEnabled()) {
            return;
        }
        String safeType = type == null || type.isBlank() ? "UNKNOWN" : type;
        String body = payload == null ? "<null>" : payload.toString();
        String stableContent = String.join("|", category, safeType, identityName, identity, body);
        StarBotBilibiliProperties.Network network = properties.getNetwork();
        if (!network.isFileDeduplicate()) {
            logger.debug("category={} type={} {}={} payload={}", category, safeType, identityName, identity, body);
            return;
        }

        DuplicateLogSuppressor.Result decision = fileDuplicates.evaluate(stableContent, fileWindowSeconds());
        String fingerprint = decision.fingerprint().substring(0, 12);
        switch (decision.action()) {
            case FULL -> logger.debug("category={} type={} {}={} payload={}",
                    category, safeType, identityName, identity, body);
            case NOTICE -> logger.debug("category={} type={} {}={} status=UNCHANGED windowSeconds={} fingerprint={}",
                    category, safeType, identityName, identity, fileWindowSeconds(), fingerprint);
            case SUMMARY -> logger.debug("category={} type={} {}={} status=STILL_UNCHANGED windowSeconds={} suppressed={} fingerprint={}",
                    category, safeType, identityName, identity, fileWindowSeconds(), decision.suppressed(), fingerprint);
            case SUPPRESS -> { }
        }
    }

    private boolean categoryEnabled(String category, String type) {
        Set<String> categories = properties.getNetwork().getFileCategories();
        if (categories == null || categories.isEmpty()) {
            return false;
        }
        String selector = category + ":" + (type == null ? "unknown" : type.toLowerCase(Locale.ROOT));
        return categories.stream().filter(value -> value != null)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .anyMatch(value -> value.equals("all") || value.equals(category) || value.equals(selector));
    }

    private long fileWindowSeconds() {
        return Math.min(7 * 24 * 60 * 60L,
                Math.max(1, properties.getNetwork().getFileDeduplicateSeconds()));
    }
}
