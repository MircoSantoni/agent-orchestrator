package dev.agentorchestrator.control.web;

import tools.jackson.databind.ObjectMapper;
import dev.agentorchestrator.control.web.McpInboxResources.InboxRef;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.MediaType;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Notifications are hints. The inbox in PostgreSQL remains the source of truth after reconnects. */
@Component
public class McpInboxSubscriptions implements SmartLifecycle {
    private static final String SUBSCRIPTION_ID = "io.modelcontextprotocol/subscriptionId";
    private static final long LIFETIME_MS = Duration.ofMinutes(5).toMillis();
    private final McpInboxResources resources;
    private final ObjectMapper json;
    private final Set<Subscription> subscriptions = ConcurrentHashMap.newKeySet();
    private volatile boolean running = true;

    private static final class Subscription {
        final Object id;
        final String owner;
        final List<InboxRef> refs;
        final Map<String, Long> counts;
        final SseEmitter emitter = new SseEmitter(LIFETIME_MS + 10_000);
        final long expiresAt = System.currentTimeMillis() + LIFETIME_MS;
        long lastSendAt = System.currentTimeMillis();
        Subscription(Object id, String owner, List<InboxRef> refs, Map<String, Long> counts) {
            this.id = id; this.owner = owner; this.refs = refs; this.counts = counts;
        }
    }

    public McpInboxSubscriptions(McpInboxResources resources, ObjectMapper json) {
        this.resources = resources;
        this.json = json;
    }

    @PreDestroy
    void closeSubscriptions() {
        for (Subscription sub : List.copyOf(subscriptions)) {
            try { finish(sub); }
            catch (IOException e) {
                subscriptions.remove(sub);
                sub.emitter.completeWithError(e);
            }
        }
    }

    @Override public void start() { running = true; }
    @Override public void stop() { running = false; closeSubscriptions(); }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MAX_VALUE; }

    public SseEmitter listen(Object id, Object rawFilter, String owner) {
        if (!(rawFilter instanceof Map<?, ?> filter)) throw ApiProblem.badRequest("notifications filter required");
        Object rawUris = filter.get("resourceSubscriptions");
        if (rawUris != null && !(rawUris instanceof List<?>))
            throw ApiProblem.badRequest("resourceSubscriptions must be an array");
        List<?> requested = rawUris == null ? List.of() : (List<?>) rawUris;
        if (requested.size() > 32 || subscriptions.size() >= 128)
            throw ApiProblem.badRequest("Subscription limit exceeded");
        List<InboxRef> refs = new ArrayList<>();
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object value : requested) {
            if (!(value instanceof String uri)) throw ApiProblem.badRequest("Invalid resource URI");
            InboxRef ref = resources.authorized(uri);
            if (!counts.containsKey(ref.uri())) {
                refs.add(ref);
                counts.put(ref.uri(), resources.messageCount(ref));
            }
        }
        Subscription sub = new Subscription(id, owner, List.copyOf(refs), counts);
        sub.emitter.onCompletion(() -> subscriptions.remove(sub));
        sub.emitter.onTimeout(() -> subscriptions.remove(sub));
        sub.emitter.onError(error -> subscriptions.remove(sub));
        try {
            push(sub, Map.of("jsonrpc", "2.0", "method", "notifications/subscriptions/acknowledged",
                    "params", Map.of("_meta", Map.of(SUBSCRIPTION_ID, id),
                            "notifications", refs.isEmpty() ? Map.of() :
                                    Map.of("resourceSubscriptions", refs.stream().map(InboxRef::uri).toList()))));
            if (refs.isEmpty()) finish(sub);
            else subscriptions.add(sub);
        } catch (IOException e) {
            sub.emitter.completeWithError(e);
        }
        return sub.emitter;
    }

    @Scheduled(fixedDelay = 1000)
    void notifyChanges() {
        for (Subscription sub : List.copyOf(subscriptions)) {
            try {
                if (System.currentTimeMillis() >= sub.expiresAt) { finish(sub); continue; }
                for (InboxRef ref : sub.refs) {
                    if (!resources.stillAccessible(ref, sub.owner)) { finish(sub); break; }
                    long current = resources.messageCount(ref);
                    Long previous = sub.counts.put(ref.uri(), current);
                    if (previous != null && previous != current)
                        push(sub, Map.of("jsonrpc", "2.0", "method", "notifications/resources/updated",
                                "params", Map.of("_meta", Map.of(SUBSCRIPTION_ID, sub.id), "uri", ref.uri())));
                }
                if (subscriptions.contains(sub) && System.currentTimeMillis() - sub.lastSendAt > 15_000) {
                    sub.emitter.send(SseEmitter.event().comment("keepalive"));
                    sub.lastSendAt = System.currentTimeMillis();
                }
            } catch (Exception e) {
                subscriptions.remove(sub);
                sub.emitter.completeWithError(e);
            }
        }
    }

    private void finish(Subscription sub) throws IOException {
        subscriptions.remove(sub);
        push(sub, Map.of("jsonrpc", "2.0", "id", sub.id,
                "result", Map.of("resultType", "complete", "_meta", Map.of(SUBSCRIPTION_ID, sub.id))));
        sub.emitter.complete();
    }

    private void push(Subscription sub, Map<String, Object> message) throws IOException {
        sub.emitter.send(SseEmitter.event().data(json.writeValueAsString(message), MediaType.APPLICATION_JSON));
        sub.lastSendAt = System.currentTimeMillis();
    }
}
