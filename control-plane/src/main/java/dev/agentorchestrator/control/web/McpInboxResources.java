package dev.agentorchestrator.control.web;

import dev.agentorchestrator.control.data.Store;
import dev.agentorchestrator.control.security.Actor;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** MCP resource identifiers are stable UUIDs, so a workspace rename does not break subscriptions. */
@Component
public class McpInboxResources {
    public record InboxRef(String uri, String kind, UUID id) {}

    private final JdbcTemplate db;
    private final Store store;
    private final Actor actor;

    public McpInboxResources(JdbcTemplate db, Store store, Actor actor) {
        this.db = db;
        this.store = store;
        this.actor = actor;
    }

    public List<Map<String, String>> list() {
        List<Map<String, String>> result = new ArrayList<>();
        result.addAll(db.query("select w.id,w.name from workspace w join project_member pm " +
                        "on pm.project_id=w.project_id and pm.user_sub=w.owner_id " +
                        "where w.owner_id=? and w.deleted_at is null order by w.created_at",
                (rs, row) -> Map.of("uri", "workspace://" + rs.getString(1) + "/inbox",
                        "name", rs.getString(2) + " inbox", "mimeType", "application/json"), actor.sub()));
        result.addAll(db.query("select a.id,a.name from agent a join orchestrator o on o.id=a.orchestrator_id " +
                        "join workspace w on w.id=o.workspace_id join project_member pm " +
                        "on pm.project_id=w.project_id and pm.user_sub=w.owner_id " +
                        "where w.owner_id=? and w.deleted_at is null order by a.created_at",
                (rs, row) -> Map.of("uri", "agent://" + rs.getString(1) + "/inbox",
                        "name", rs.getString(2) + " inbox", "mimeType", "application/json"), actor.sub()));
        return result;
    }

    public InboxRef authorized(String value) {
        try {
            URI uri = URI.create(value);
            if (!"/inbox".equals(uri.getPath()) || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getUserInfo() != null || uri.getPort() != -1) throw new IllegalArgumentException();
            UUID id = UUID.fromString(uri.getHost());
            if ("workspace".equals(uri.getScheme())) store.ownWorkspace(id);
            else if ("agent".equals(uri.getScheme())) store.ownAgent(id);
            else throw new IllegalArgumentException();
            return new InboxRef(uri.getScheme() + "://" + id + "/inbox", uri.getScheme(), id);
        } catch (RuntimeException e) {
            if (e instanceof ApiProblem) throw e;
            throw new IllegalArgumentException("Invalid inbox resource URI");
        }
    }

    public String read(InboxRef ref) {
        authorized(ref.uri());
        String filter = "workspace".equals(ref.kind()) ? "m.to_workspace_id=?" :
                "m.to_workspace_id=(select o.workspace_id from agent a join orchestrator o on o.id=a.orchestrator_id where a.id=?) " +
                "and (m.to_agent_id=? or m.to_agent_id is null)";
        String sql = "select row_to_json(x)::text from (select m.id,m.type,m.subject,m.task_id,m.status," +
                "m.from_workspace_id,m.from_agent_id,m.created_at from agent_message m where " + filter +
                " and m.status in ('PENDING','DELIVERED','READ') order by m.created_at desc limit 100) x";
        String messages = "workspace".equals(ref.kind()) ? store.many(sql, ref.id()) :
                store.many(sql, ref.id(), ref.id());
        return "{\"messages\":" + messages + "}";
    }

    public long messageCount(InboxRef ref) {
        String sql = "workspace".equals(ref.kind()) ?
                "select count(*) from agent_message where to_workspace_id=?" :
                "select count(*) from agent_message m join agent a on a.id=? " +
                "join orchestrator o on o.id=a.orchestrator_id where m.to_workspace_id=o.workspace_id " +
                "and (m.to_agent_id=? or m.to_agent_id is null)";
        Long value = "workspace".equals(ref.kind()) ? db.queryForObject(sql, Long.class, ref.id()) :
                db.queryForObject(sql, Long.class, ref.id(), ref.id());
        return value == null ? 0 : value;
    }

    public boolean stillAccessible(InboxRef ref, String owner) {
        String sql = "workspace".equals(ref.kind()) ?
                "select count(*) from workspace w join project_member pm on pm.project_id=w.project_id " +
                "where w.id=? and w.owner_id=? and w.deleted_at is null and pm.user_sub=?" :
                "select count(*) from agent a join orchestrator o on o.id=a.orchestrator_id " +
                "join workspace w on w.id=o.workspace_id join project_member pm on pm.project_id=w.project_id " +
                "where a.id=? and w.owner_id=? and w.deleted_at is null and pm.user_sub=?";
        Integer count = db.queryForObject(sql, Integer.class, ref.id(), owner, owner);
        return count != null && count == 1;
    }
}
