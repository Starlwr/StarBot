package com.starlwr.bot.bilibili.service;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.core.plugin.StarBotComponent;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
@StarBotComponent
public class BilibiliFailureIncidentReporter {
    public enum Category {
        WS_TLS_ABNORMAL_CLOSE,
        WS_HEARTBEAT_TIMEOUT,
        WS_HANDSHAKE_FAILURE,
        WS_ABNORMAL_CLOSE,
        TELEMETRY_HTTP_504
    }

    public record Observation(Category category, long roomId, String host, long generation,
                              long uptimeMillis, long heartbeatAckAgeMillis, Throwable error) {}

    public record Decision(boolean suppressWarning, boolean includeStack) {
        static Decision individual(boolean includeStack) { return new Decision(false, includeStack); }
        static Decision suppressed() { return new Decision(true, false); }
    }

    private final StarBotBilibiliProperties properties;
    private final Map<String, Incident> incidents = new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, runnable -> {
        Thread thread = new Thread(runnable, "bilibili-incident-summary");
        thread.setDaemon(true);
        return thread;
    });

    public BilibiliFailureIncidentReporter(StarBotBilibiliProperties properties) {
        this.properties = properties;
        scheduler.setRemoveOnCancelPolicy(true);
    }

    public Decision record(Observation observation) {
        if (!properties.getLive().isDisconnectSummaryEnabled()) {
            return Decision.individual(true);
        }
        String signature = signature(observation);
        Incident incident = incidents.computeIfAbsent(signature, ignored -> new Incident(signature, observation.category()));
        synchronized (incident) {
            long now = System.currentTimeMillis();
            long windowMillis = seconds(properties.getLive().getDisconnectSummaryWindowSeconds());
            boolean includeStack = !incident.stackRecorded;
            incident.stackRecorded = true;
            int threshold = Math.max(2, properties.getLive().getDisconnectSummaryRoomThreshold());
            int sampleLimit = Math.max(1, properties.getLive().getDisconnectSummarySampleLimit());
            if (!incident.open) {
                while (!incident.recent.isEmpty()
                        && now - incident.recent.peekFirst().timestampMillis > windowMillis) {
                    incident.recent.removeFirst();
                }
                if (incident.recent.isEmpty() && incident.lastObservedAt > 0) {
                    incident.reset();
                    includeStack = true;
                    incident.stackRecorded = true;
                }
                incident.recent.addLast(new Recent(now, observation));
                incident.lastObservedAt = now;
                long recentRooms = incident.recent.stream().map(item -> item.observation.roomId()).distinct().count();
                if (recentRooms >= threshold) {
                    incident.open = true;
                    incident.resetMetrics();
                    incident.recent.forEach(item -> incident.addMetrics(item.observation, item.timestampMillis, sampleLimit));
                    log.warn("Bilibili 集中故障已聚合: category={}, rooms={}, events={}, windowSeconds={}, hosts={}, sampleRooms={}",
                            incident.category, incident.distinctRooms.size(), incident.totalEvents,
                            Math.max(1, properties.getLive().getDisconnectSummaryWindowSeconds()),
                            incident.hostCounts, incident.sampleRooms);
                }
            } else {
                incident.addMetrics(observation, now, sampleLimit);
            }
            scheduleQuietSummary(incident, now);
            return incident.open ? Decision.suppressed() : Decision.individual(includeStack);
        }
    }

    private void scheduleQuietSummary(Incident incident, long observedAt) {
        long quietSeconds = Math.max(1, properties.getLive().getDisconnectSummaryQuietSeconds());
        if (incident.quietTask != null) incident.quietTask.cancel(false);
        incident.quietTask = scheduler.schedule(
                () -> finishIfQuiet(incident, observedAt, quietSeconds), quietSeconds, TimeUnit.SECONDS);
    }

    private void finishIfQuiet(Incident incident, long observedAt, long quietSeconds) {
        synchronized (incident) {
            if (incident.lastObservedAt != observedAt
                    || System.currentTimeMillis() - incident.lastObservedAt < TimeUnit.SECONDS.toMillis(quietSeconds)) {
                return;
            }
            incidents.remove(incident.signature, incident);
            if (!incident.open) return;
            long minimumUptime = incident.minimumUptime == Long.MAX_VALUE ? -1 : incident.minimumUptime;
            long minimumAckAge = incident.minimumAckAge == Long.MAX_VALUE ? -1 : incident.minimumAckAge;
            log.warn("Bilibili 集中故障已结束: category={}, rooms={}, events={}, durationMs={}, hosts={}, "
                            + "uptimeMsRange={}..{}, heartbeatAckAgeMsRange={}..{}, sampleRooms={}",
                    incident.category, incident.distinctRooms.size(), incident.totalEvents,
                    incident.lastObservedAt - incident.firstObservedAt, incident.sortedHosts(),
                    minimumUptime, incident.maximumUptime,
                    minimumAckAge, incident.maximumAckAge, incident.sampleRooms);
        }
    }

    private String signature(Observation observation) {
        Throwable root = rootCause(observation.error());
        String rootType = root == null ? "none" : root.getClass().getName();
        return observation.category().name() + ':' + rootType;
    }

    public static Throwable rootCause(Throwable error) {
        if (error == null) return null;
        Throwable current = error;
        Set<Throwable> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        while (current.getCause() != null && visited.add(current)) current = current.getCause();
        return current;
    }

    public static boolean isTlsAbnormalClose(int closeCode, Throwable error) {
        if (closeCode != 1006) return false;
        Throwable current = error;
        Set<Throwable> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        while (current != null && visited.add(current)) {
            String name = current.getClass().getName();
            String message = current.getMessage();
            if (name.contains("SSL") || name.contains("Tls")
                    || message != null && (message.contains("SSLEngineResult") || message.contains("unwrap()"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private long seconds(int value) {
        return TimeUnit.SECONDS.toMillis(Math.max(1, value));
    }

    @PreDestroy
    public void close() {
        scheduler.shutdownNow();
        incidents.clear();
    }

    private static final class Incident {
        private final String signature;
        private final Category category;
        private final ArrayDeque<Recent> recent = new ArrayDeque<>();
        private final Set<Long> distinctRooms = new LinkedHashSet<>();
        private final Set<Long> sampleRooms = new LinkedHashSet<>();
        private final Map<String, Integer> hostCounts = new HashMap<>();
        private boolean open;
        private boolean stackRecorded;
        private ScheduledFuture<?> quietTask;
        private long totalEvents;
        private long firstObservedAt;
        private long lastObservedAt;
        private long minimumUptime = Long.MAX_VALUE;
        private long maximumUptime;
        private long minimumAckAge = Long.MAX_VALUE;
        private long maximumAckAge;

        private Incident(String signature, Category category) {
            this.signature = signature;
            this.category = category;
        }

        private void addMetrics(Observation observation, long now, int sampleLimit) {
            if (totalEvents == 0) firstObservedAt = now;
            lastObservedAt = now;
            totalEvents++;
            distinctRooms.add(observation.roomId());
            if (sampleRooms.size() < sampleLimit) sampleRooms.add(observation.roomId());
            hostCounts.merge(observation.host() == null || observation.host().isBlank() ? "unknown" : observation.host(), 1, Integer::sum);
            if (observation.uptimeMillis() >= 0) {
                minimumUptime = Math.min(minimumUptime, observation.uptimeMillis());
                maximumUptime = Math.max(maximumUptime, observation.uptimeMillis());
            }
            if (observation.heartbeatAckAgeMillis() >= 0) {
                minimumAckAge = Math.min(minimumAckAge, observation.heartbeatAckAgeMillis());
                maximumAckAge = Math.max(maximumAckAge, observation.heartbeatAckAgeMillis());
            }
        }

        private List<Map.Entry<String, Integer>> sortedHosts() {
            List<Map.Entry<String, Integer>> result = new ArrayList<>(hostCounts.entrySet());
            result.sort(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()));
            return result;
        }

        private void reset() {
            recent.clear();
            resetMetrics();
            open = false;
            stackRecorded = false;
            lastObservedAt = 0;
        }

        private void resetMetrics() {
            distinctRooms.clear();
            sampleRooms.clear();
            hostCounts.clear();
            totalEvents = 0;
            firstObservedAt = 0;
            minimumUptime = Long.MAX_VALUE;
            maximumUptime = 0;
            minimumAckAge = Long.MAX_VALUE;
            maximumAckAge = 0;
        }
    }

    private record Recent(long timestampMillis, Observation observation) {}
}
