package dev.agentorchestrator.control.events;

import dev.agentorchestrator.control.data.Store;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class MaintenanceJobs {
    private final JdbcTemplate db;
    private final Store store;
    public MaintenanceJobs(JdbcTemplate db, Store store) { this.db = db; this.store = store; }

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void expireIntents() {
        for (Map<String, Object> row : db.queryForList("update resource_intent set status='EXPIRED',updated_at=now() where status='ACTIVE' and lease_until<now() returning id,project_id,workspace_id,agent_id,task_id"))
            store.activity((UUID)row.get("project_id"), (UUID)row.get("workspace_id"), (UUID)row.get("agent_id"), (UUID)row.get("task_id"), "RESOURCE_INTENT_EXPIRED", "Intent lease expired", (UUID)row.get("id"));
    }

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void markOffline() {
        for (Map<String, Object> row : db.queryForList("update workspace set status='OFFLINE',updated_at=now() where status<>'OFFLINE' and last_heartbeat_at<now()-interval '45 seconds' returning id,project_id"))
            store.activity((UUID)row.get("project_id"), (UUID)row.get("id"), null, null, "WORKSPACE_STATUS_CHANGED", "Workspace offline", (UUID)row.get("id"));
        for (Map<String, Object> row : db.queryForList("update orchestrator set status='OFFLINE',updated_at=now() where status<>'OFFLINE' and last_heartbeat_at<now()-interval '45 seconds' returning id,workspace_id")) {
            UUID workspaceId = (UUID)row.get("workspace_id");
            store.activity(store.workspaceProject(workspaceId), workspaceId, null, null, "ORCHESTRATOR_STATUS_CHANGED", "Orchestrator offline", (UUID)row.get("id"));
        }
        for (Map<String, Object> row : db.queryForList("update agent set status='OFFLINE',updated_at=now() where status<>'OFFLINE' and last_heartbeat_at<now()-interval '45 seconds' returning id,orchestrator_id")) {
            UUID workspaceId = db.queryForObject("select workspace_id from orchestrator where id=?", UUID.class, row.get("orchestrator_id"));
            store.activity(store.workspaceProject(workspaceId), workspaceId, (UUID)row.get("id"), null, "AGENT_STATUS_CHANGED", "Agent offline", (UUID)row.get("id"));
        }
    }
}
