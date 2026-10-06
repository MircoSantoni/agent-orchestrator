package dev.agentorchestrator.control.data;

import dev.agentorchestrator.control.security.Actor;
import dev.agentorchestrator.control.web.ApiProblem;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CoordinationService {
    private final JdbcTemplate db;
    private final Store store;
    private final Actor actor;

    public CoordinationService(JdbcTemplate db, Store store, Actor actor) {
        this.db = db; this.store = store; this.actor = actor;
    }

    private UUID id(String sql, Object... args) {
        List<UUID> rows = db.queryForList(sql, UUID.class, args);
        if (rows.isEmpty()) throw ApiProblem.notFound("Resource not found");
        return rows.getFirst();
    }

    private String path(String raw) {
        if (raw == null || raw.isBlank()) throw ApiProblem.badRequest("Resource path required");
        String path = raw.replace('\\', '/').replaceAll("/+", "/");
        if (path.startsWith("/") || path.matches("^[A-Za-z]:.*") || List.of(path.split("/")).contains(".."))
            throw ApiProblem.badRequest("Resource path must be relative to the repository");
        List<String> parts = new ArrayList<>();
        for (String part : path.split("/")) if (!part.isBlank() && !part.equals(".")) parts.add(part);
        return parts.isEmpty() ? "." : String.join("/", parts);
    }

    private boolean overlaps(String firstPath, String firstType, String secondPath, String secondType) {
        if (firstType.equals("REPOSITORY") || secondType.equals("REPOSITORY")) return true;
        if (firstPath.equals(secondPath)) return true;
        boolean firstContainer = List.of("DIRECTORY", "MODULE", "SERVICE").contains(firstType);
        boolean secondContainer = List.of("DIRECTORY", "MODULE", "SERVICE").contains(secondType);
        return firstContainer && secondPath.startsWith(firstPath + "/")
                || secondContainer && firstPath.startsWith(secondPath + "/");
    }

    @Transactional
    public UUID createIntent(UUID projectId, UUID workspaceId, UUID agentId, UUID taskId,
                             String resourceType, String resourcePath, String intentType, int leaseSeconds) {
        store.member(projectId);
        store.ownWorkspace(workspaceId);
        if (!store.workspaceProject(workspaceId).equals(projectId) || !store.agentWorkspace(agentId).equals(workspaceId))
            throw ApiProblem.forbidden("Agent does not belong to workspace or project");
        if (taskId != null) {
            if (!store.taskProject(taskId).equals(projectId)) throw ApiProblem.badRequest("Task is in another project");
            Integer owned = db.queryForObject("select count(*) from task where id=? and owner_workspace_id=? and executor_agent_id=? and status in ('CLAIMED','IN_PROGRESS','BLOCKED')", Integer.class, taskId, workspaceId, agentId);
            if (owned == null || owned != 1) throw ApiProblem.forbidden("Agent does not own this active task");
        }
        if (!List.of("FILE","DIRECTORY","MODULE","SERVICE","REPOSITORY").contains(resourceType)
                || !List.of("READ","WRITE").contains(intentType) || leaseSeconds < 30 || leaseSeconds > 3600)
            throw ApiProblem.badRequest("Invalid resource intent");
        String normalized = path(resourcePath);
        if (normalized.equals(".") && !resourceType.equals("REPOSITORY"))
            throw ApiProblem.badRequest("Only a repository intent may use the repository root");
        // Serialize all intent creation in a project so parent/child paths cannot race.
        db.queryForObject("select pg_advisory_xact_lock(hashtext(?))", Object.class, projectId.toString());
        List<Map<String, Object>> conflicts = db.queryForList(
                "select resource_path,resource_type,intent_type,agent_id from resource_intent " +
                "where project_id=? and status='ACTIVE' and lease_until>now() and agent_id<>?", projectId, agentId)
                .stream().filter(other -> overlaps(normalized, resourceType,
                        (String) other.get("resource_path"), (String) other.get("resource_type")))
                .filter(other -> !(intentType.equals("READ") && "READ".equals(other.get("intent_type")))).toList();
        UUID id = id("insert into resource_intent(project_id,workspace_id,agent_id,task_id,resource_type,resource_path,intent_type,lease_until) " +
                "values(?,?,?,?,?,?,?,now()+(? * interval '1 second')) returning id",
                projectId, workspaceId, agentId, taskId, resourceType, normalized, intentType, leaseSeconds);
        store.activity(projectId, workspaceId, agentId, taskId, "RESOURCE_INTENT_CREATED", "Intent " + intentType + " " + normalized, id);
        if (!conflicts.isEmpty()) {
            String severity = intentType.equals("WRITE") && conflicts.stream().anyMatch(other -> "WRITE".equals(other.get("intent_type"))) ? "HIGH" : "WARNING";
            String agentsJson = "[\"" + agentId + "\"" + conflicts.stream()
                    .map(other -> ",\"" + other.get("agent_id") + "\"").distinct().reduce("", String::concat) + "]";
            db.update("insert into activity_event(project_id,workspace_id,agent_id,task_id,type,summary,payload) " +
                    "values(?,?,?,?,'RESOURCE_CONFLICT_DETECTED',?,jsonb_build_object('entityId',?::text,'resourcePath',?,'severity',?,'agents',?::jsonb))",
                    projectId, workspaceId, agentId, taskId, severity + " conflict on " + normalized,
                    id.toString(), normalized, severity, agentsJson);
        }
        return id;
    }

    @Transactional
    public void renewIntent(UUID intentId, int leaseSeconds) {
        UUID workspaceId = id("select workspace_id from resource_intent where id=?", intentId);
        store.ownWorkspace(workspaceId);
        if (leaseSeconds < 30 || leaseSeconds > 3600) throw ApiProblem.badRequest("Invalid lease duration");
        int changed = db.update("update resource_intent set lease_until=now()+(? * interval '1 second'),updated_at=now() where id=? and status='ACTIVE' and lease_until>now()", leaseSeconds, intentId);
        if (changed != 1) throw ApiProblem.conflict("Intent has expired or been released");
    }

    @Transactional
    public void releaseIntent(UUID intentId) {
        UUID workspaceId = id("select workspace_id from resource_intent where id=?", intentId);
        store.ownWorkspace(workspaceId);
        int changed = db.update("update resource_intent set status='RELEASED',updated_at=now() where id=? and status='ACTIVE'", intentId);
        if (changed != 1) throw ApiProblem.conflict("Intent is no longer active");
        UUID projectId = store.workspaceProject(workspaceId);
        store.activity(projectId, workspaceId, null, null, "RESOURCE_INTENT_RELEASED", "Intent released", intentId);
    }

    public String intents(UUID projectId) {
        store.member(projectId);
        return store.many("select row_to_json(x)::text from (select * from resource_intent where project_id=? order by created_at desc) x", projectId);
    }

    @Transactional
    public UUID createContext(UUID projectId, UUID workspaceId, UUID agentId, UUID taskId,
                              String type, String title, String content) {
        store.member(projectId);
        if (workspaceId != null) {
            store.ownWorkspace(workspaceId);
            if (!store.workspaceProject(workspaceId).equals(projectId)) throw ApiProblem.forbidden("Workspace is in another project");
        }
        if (agentId != null && (workspaceId == null || !store.agentWorkspace(agentId).equals(workspaceId)))
            throw ApiProblem.forbidden("Agent is outside workspace");
        if (taskId != null && !store.taskProject(taskId).equals(projectId)) throw ApiProblem.badRequest("Task is in another project");
        if (!List.of("FACT","DISCOVERY","ASSUMPTION","PROPOSAL").contains(type)) throw ApiProblem.badRequest("Invalid context type");
        String status = type.equals("PROPOSAL") ? "PENDING_APPROVAL" : "ACTIVE";
        UUID id = id("insert into context_entry(project_id,type,title,content,status,created_by_workspace_id,created_by_agent_id,related_task_id) values(?,?,?,?,?,?,?,?) returning id",
                projectId, type, title, content, status, workspaceId, agentId, taskId);
        store.activity(projectId, workspaceId, agentId, taskId, type.equals("PROPOSAL") ? "PROPOSAL_CREATED" : "CONTEXT_CREATED", type + " created", id);
        return id;
    }

    @Transactional
    public UUID approve(UUID proposalId) {
        actor.requireHuman();
        UUID projectId = id("select project_id from context_entry where id=?", proposalId);
        store.member(projectId);
        int changed = db.update("update context_entry set status='SUPERSEDED',approved_by=?,approved_at=now(),updated_at=now() where id=? and type='PROPOSAL' and status='PENDING_APPROVAL'", actor.sub(), proposalId);
        if (changed != 1) throw ApiProblem.conflict("Proposal is no longer pending");
        UUID decisionId = id("insert into context_entry(project_id,type,title,content,status,approved_by,approved_at,related_task_id,metadata) " +
                "select project_id,'DECISION',title,content,'ACTIVE',?,now(),related_task_id,jsonb_build_object('proposalId',id) from context_entry where id=? returning id",
                actor.sub(), proposalId);
        store.activity(projectId, null, null, null, "PROPOSAL_APPROVED", "Proposal approved by " + actor.sub(), proposalId);
        return decisionId;
    }

    @Transactional
    public void reject(UUID proposalId) {
        actor.requireHuman();
        UUID projectId = id("select project_id from context_entry where id=?", proposalId);
        store.member(projectId);
        int changed = db.update("update context_entry set status='REJECTED',approved_by=?,approved_at=now(),updated_at=now() where id=? and type='PROPOSAL' and status='PENDING_APPROVAL'", actor.sub(), proposalId);
        if (changed != 1) throw ApiProblem.conflict("Proposal is no longer pending");
        store.activity(projectId, null, null, null, "PROPOSAL_REJECTED", "Proposal rejected by " + actor.sub(), proposalId);
    }

    public String context(UUID projectId) {
        store.member(projectId);
        return store.many("select row_to_json(x)::text from (select * from context_entry where project_id=? order by created_at desc) x", projectId);
    }

    public String contextEntry(UUID id) {
        UUID projectId = id("select project_id from context_entry where id=?", id);
        store.member(projectId);
        return store.one("select row_to_json(x)::text from (select * from context_entry where id=?) x", id);
    }

    @Transactional
    public UUID sendMessage(UUID projectId, UUID fromWorkspaceId, UUID fromAgentId, UUID toWorkspaceId,
                            UUID toAgentId, UUID taskId, String type, String subject, String body) {
        store.member(projectId);
        store.ownWorkspace(fromWorkspaceId);
        if (!store.workspaceProject(fromWorkspaceId).equals(projectId)) throw ApiProblem.forbidden("Sender is in another project");
        if (fromAgentId != null && !store.agentWorkspace(fromAgentId).equals(fromWorkspaceId)) throw ApiProblem.forbidden("Sender agent is outside workspace");
        if (toWorkspaceId == null || !store.activeWorkspaceProject(toWorkspaceId).equals(projectId)) throw ApiProblem.badRequest("Recipient workspace required in same project");
        if (toAgentId != null && !store.agentWorkspace(toAgentId).equals(toWorkspaceId)) throw ApiProblem.badRequest("Recipient agent is outside workspace");
        if (taskId != null && !store.taskProject(taskId).equals(projectId)) throw ApiProblem.badRequest("Task is in another project");
        if (!List.of("HELP_REQUEST","CONFLICT_WARNING","TASK_HANDOFF","REVIEW_REQUEST","DISCOVERY","BLOCKER","ARTIFACT_READY","TASK_COMPLETED","COORDINATION_REQUEST").contains(type))
            throw ApiProblem.badRequest("Invalid message type");
        if (type.equals("TASK_HANDOFF") && taskId == null) throw ApiProblem.badRequest("Handoff requires a parent task");
        if (type.equals("TASK_HANDOFF")) {
            Integer owned = db.queryForObject("select count(*) from task where id=? and owner_workspace_id=? and (?::uuid is null or executor_agent_id=?)", Integer.class,
                    taskId, fromWorkspaceId, fromAgentId, fromAgentId);
            if (owned == null || owned != 1) throw ApiProblem.forbidden("Sender does not own the parent task");
        }
        UUID id = id("insert into agent_message(project_id,from_workspace_id,from_agent_id,to_workspace_id,to_agent_id,type,task_id,subject,body) values(?,?,?,?,?,?,?,?,?) returning id",
                projectId, fromWorkspaceId, fromAgentId, toWorkspaceId, toAgentId, type, taskId, subject, body);
        store.activity(projectId, fromWorkspaceId, fromAgentId, taskId, "MESSAGE_CREATED", type + " sent", id);
        return id;
    }

    public String message(UUID id) {
        UUID projectId = id("select project_id from agent_message where id=?", id);
        store.member(projectId);
        return store.one("select row_to_json(x)::text from (select * from agent_message where id=?) x", id);
    }

    /** Project members see a short preview; the complete body remains in the recipient inbox. */
    public String messageFlow(UUID projectId) {
        store.member(projectId);
        return store.many("select row_to_json(x)::text from (select m.id,m.from_workspace_id,m.from_agent_id," +
                "m.to_workspace_id,m.to_agent_id,source.name as from_workspace_name,target.name as to_workspace_name," +
                "source.owner_display_name as from_owner_name,target.owner_display_name as to_owner_name," +
                "m.type,m.status,left(coalesce(nullif(trim(m.subject),''),m.body),180) as summary," +
                "m.created_at from agent_message m join workspace source on source.id=m.from_workspace_id " +
                "left join workspace target on target.id=m.to_workspace_id where m.project_id=? " +
                "order by m.created_at desc limit 100) x", projectId);
    }

    public String inbox(UUID workspaceId) {
        store.ownWorkspace(workspaceId);
        return store.many("select row_to_json(x)::text from (select m.*,source.name as from_workspace_name " +
                "from agent_message m join workspace source on source.id=m.from_workspace_id " +
                "where m.to_workspace_id=? order by m.created_at desc) x", workspaceId);
    }

    public String personalInbox(UUID projectId) {
        store.member(projectId);
        return store.many("select row_to_json(x)::text from (select m.*,source.name as from_workspace_name," +
                "target.name as to_workspace_name from agent_message m " +
                "join workspace source on source.id=m.from_workspace_id " +
                "join workspace target on target.id=m.to_workspace_id " +
                "where m.project_id=? and target.owner_id=? order by m.created_at desc limit 200) x",
                projectId, store.currentUser());
    }

    @Transactional
    public void read(UUID messageId) {
        UUID workspaceId = id("select to_workspace_id from agent_message where id=?", messageId);
        store.ownWorkspace(workspaceId);
        db.update("update agent_message set status='READ',read_at=now() where id=? and status in ('PENDING','DELIVERED')", messageId);
    }

    @Transactional
    public void acknowledge(UUID messageId) {
        UUID workspaceId = id("select to_workspace_id from agent_message where id=?", messageId);
        store.ownWorkspace(workspaceId);
        db.update("update agent_message set status='ACKNOWLEDGED',read_at=coalesce(read_at,now()) where id=? and status in ('PENDING','DELIVERED','READ')", messageId);
    }

    @Transactional
    public UUID acceptHandoff(UUID messageId) {
        UUID workspaceId = id("select to_workspace_id from agent_message where id=?", messageId);
        store.ownWorkspace(workspaceId);
        String type = store.one("select type from agent_message where id=?", messageId);
        if (!type.equals("TASK_HANDOFF")) throw ApiProblem.badRequest("Message is not a handoff");
        int changed = db.update("update agent_message set status='ACKNOWLEDGED',read_at=coalesce(read_at,now()) where id=? and accepted_task_id is null and status in ('PENDING','DELIVERED','READ','ACKNOWLEDGED')", messageId);
        if (changed != 1) throw ApiProblem.conflict("Handoff was already accepted");
        UUID parentTaskId = id("select task_id from agent_message where id=?", messageId);
        UUID projectId = store.taskProject(parentTaskId);
        UUID childId = id("insert into task(project_id,parent_task_id,title,description,status,owner_workspace_id,created_by) " +
                "select project_id,task_id,coalesce(subject,'Handoff subtarea'),body,'READY',to_workspace_id,? from agent_message where id=? returning id",
                actor.sub(), messageId);
        db.update("update agent_message set accepted_task_id=? where id=?", childId, messageId);
        store.activity(projectId, workspaceId, null, childId, "TASK_HANDOFF_ACCEPTED", "Handoff subtarea accepted", messageId);
        return childId;
    }
}
