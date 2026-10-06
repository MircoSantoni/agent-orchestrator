package dev.agentorchestrator.control.events;

import dev.agentorchestrator.control.data.Store;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class ActivityStream {
    private record Event(long id, String type, String data) {}
    private final JdbcTemplate db;
    private final Store store;
    private final Set<Subscription> subscriptions = ConcurrentHashMap.newKeySet();

    private static final class Subscription {
        final UUID projectId;
        final SseEmitter emitter = new SseEmitter(0L);
        final Set<Long> sent = ConcurrentHashMap.newKeySet();
        final long firstId;
        long lastId;
        long lastSendAt = System.currentTimeMillis();
        Subscription(UUID projectId, long lastId) { this.projectId = projectId; this.lastId = lastId; this.firstId = lastId; }
    }

    public ActivityStream(JdbcTemplate db, Store store) { this.db = db; this.store = store; }

    public SseEmitter subscribe(UUID projectId, Long lastEventId) {
        store.member(projectId);
        Long current = db.queryForObject("select coalesce(max(id),0) from activity_event where project_id=?", Long.class, projectId);
        Subscription subscription = new Subscription(projectId, lastEventId == null ? current : lastEventId);
        subscriptions.add(subscription);
        subscription.emitter.onCompletion(() -> subscriptions.remove(subscription));
        subscription.emitter.onTimeout(() -> subscriptions.remove(subscription));
        subscription.emitter.onError(error -> subscriptions.remove(subscription));
        try { subscription.emitter.send(SseEmitter.event().comment("connected")); }
        catch (IOException e) { subscriptions.remove(subscription); subscription.emitter.completeWithError(e); }
        return subscription.emitter;
    }

    public String activity(UUID projectId, long afterId, int limit) {
        store.member(projectId);
        int pageSize = Math.max(1, Math.min(limit, 200));
        return store.many("select row_to_json(x)::text from (select id,event_id,project_id,workspace_id,agent_id,task_id,type,summary,payload,created_at from activity_event where project_id=? and id>? order by id limit ?) x", projectId, afterId, pageSize);
    }

    @Scheduled(fixedDelay = 1000)
    void broadcast() {
        for (Subscription s : new ArrayList<>(subscriptions)) {
            try {
                List<Event> events = db.query("select id,type,json_build_object('id',event_id,'type',type,'projectId',project_id,'timestamp',created_at,'payload',payload)::text " +
                        "from activity_event where project_id=? and id>? order by id limit 200",
                        (rs, index) -> new Event(rs.getLong(1), rs.getString(2), rs.getString(3)),
                        s.projectId, Math.max(0, s.lastId - 100));
                for (Event event : events) {
                    if (event.id() > s.firstId && s.sent.add(event.id()))
                    {
                        s.emitter.send(SseEmitter.event().id(Long.toString(event.id())).name(event.type()).data(event.data(), MediaType.APPLICATION_JSON));
                        s.lastSendAt = System.currentTimeMillis();
                    }
                    s.lastId = Math.max(s.lastId, event.id());
                }
                if (System.currentTimeMillis() - s.lastSendAt > 15000) {
                    s.emitter.send(SseEmitter.event().comment("keepalive"));
                    s.lastSendAt = System.currentTimeMillis();
                }
                s.sent.removeIf(id -> id < s.lastId - 100);
            } catch (Exception e) {
                subscriptions.remove(s);
                s.emitter.completeWithError(e);
            }
        }
    }
}
