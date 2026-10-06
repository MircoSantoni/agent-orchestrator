package dev.agentorchestrator.control.data;

import dev.agentorchestrator.control.security.Actor;
import dev.agentorchestrator.control.web.ApiProblem;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Store {
    private final JdbcTemplate db;
    private final Actor actor;
    private final OrganizationRepository organizations;
    private final ProjectRepository projects;

    public Store(JdbcTemplate db, Actor actor, OrganizationRepository organizations, ProjectRepository projects) {
        this.db = db; this.actor = actor; this.organizations = organizations; this.projects = projects;
    }

    public String currentUser() { return actor.sub(); }

    @Transactional
    public void markCurrentUserActive() {
        db.update("update project_member set invitation_status='ACTIVE' where user_sub=? and invitation_status='INVITED'", actor.sub());
    }

    public String one(String sql, Object... args) {
        try { return db.queryForObject(sql, String.class, args); }
        catch (EmptyResultDataAccessException e) { throw ApiProblem.notFound("Resource not found"); }
    }

    public String many(String sql, Object... args) {
        List<String> rows = db.queryForList(sql, String.class, args);
        return "[" + String.join(",", rows) + "]";
    }

    private UUID uuid(String sql, Object... args) {
        try { return db.queryForObject(sql, UUID.class, args); }
        catch (EmptyResultDataAccessException e) { throw ApiProblem.notFound("Resource not found"); }
    }

    public void member(UUID projectId) {
        Integer count = db.queryForObject("select count(*) from project_member where project_id=? and user_sub=?", Integer.class, projectId, actor.sub());
        if (count == null || count == 0) throw ApiProblem.forbidden("Not a project member");
    }

    public UUID workspaceProject(UUID workspaceId) {
        return uuid("select project_id from workspace where id=?", workspaceId);
    }

    public void ownWorkspace(UUID workspaceId) {
        UUID projectId = workspaceProject(workspaceId);
        member(projectId);
        String owner = one("select owner_id from workspace where id=?", workspaceId);
        if (!owner.equals(actor.sub())) throw ApiProblem.forbidden("Workspace belongs to another person");
    }

    public UUID agentWorkspace(UUID agentId) {
        return uuid("select o.workspace_id from agent a join orchestrator o on o.id=a.orchestrator_id where a.id=?", agentId);
    }

    public void ownAgent(UUID agentId) { ownWorkspace(agentWorkspace(agentId)); }

    public UUID taskProject(UUID taskId) { return uuid("select project_id from task where id=?", taskId); }

    public void activity(UUID projectId, UUID workspaceId, UUID agentId, UUID taskId, String type, String summary, UUID entityId) {
        // Serialize event IDs within a project through commit, so SSE cursors cannot skip a late commit.
        db.queryForObject("select id from project where id=? for update", UUID.class, projectId);
        db.update("insert into activity_event(project_id,workspace_id,agent_id,task_id,type,summary,payload) values(?,?,?,?,?,?,jsonb_build_object('entityId',?::text))",
                projectId, workspaceId, agentId, taskId, type, summary, entityId.toString());
    }

    @Transactional
    public UUID createOrganization(String name, String slug) {
        actor.requireHuman();
        OrganizationEntity organization = organizations.saveAndFlush(new OrganizationEntity(name, slug));
        db.update("update organization set owner_sub=? where id=?", actor.sub(), organization.id);
        return organization.id;
    }

    @Transactional
    public UUID createProject(UUID organizationId, String name, String slug) {
        actor.requireHuman();
        String owner = one("select owner_sub from organization where id=?", organizationId);
        if (!actor.sub().equals(owner)) throw ApiProblem.forbidden("Only the organization owner can create projects");
        UUID id = projects.saveAndFlush(new ProjectEntity(organizationId, name, slug)).id;
        db.update("insert into project_member(project_id,user_sub,display_name,role) values(?,?,?,'ADMIN')", id, actor.sub(), actor.sub());
        return id;
    }

    public void requireAdmin(UUID projectId) {
        actor.requireHuman();
        member(projectId);
        String role = one("select role from project_member where project_id=? and user_sub=?", projectId, actor.sub());
        if (!role.equals("ADMIN")) throw ApiProblem.forbidden("Only project admins can add members");
    }

    @Transactional
    public void addMember(UUID projectId, String userSub, String displayName, String email, String invitationStatus) {
        requireAdmin(projectId);
        db.update("insert into project_member(project_id,user_sub,display_name,email,invitation_status) values(?,?,?,?,?) " +
                "on conflict(project_id,user_sub) do update set display_name=excluded.display_name," +
                "email=coalesce(excluded.email,project_member.email)," +
                "invitation_status=case when excluded.email is null then project_member.invitation_status else excluded.invitation_status end",
                projectId, userSub, displayName, email, invitationStatus);
        activity(projectId, null, null, null, "PROJECT_MEMBER_ADDED", "Project member added", projectId);
    }

    public boolean invitedMemberEmail(UUID projectId, String email) {
        Integer count = db.queryForObject("select count(*) from project_member where project_id=? and lower(email)=? " +
                "and invitation_status='INVITED'", Integer.class, projectId, email);
        return count != null && count > 0;
    }

    @Transactional
    public void markMemberEmailActive(UUID projectId, String email) {
        db.update("update project_member set invitation_status='ACTIVE' where project_id=? and lower(email)=?",
                projectId, email);
    }

    @Transactional
    public UUID registerWorkspace(UUID projectId, String name, String hostname, String os, String displayName) {
        member(projectId);
        UUID id = uuid("insert into workspace(project_id,owner_id,owner_display_name,name,hostname,os) values(?,?,?,?,?,?) " +
                "on conflict(project_id,owner_id,name) do update set hostname=excluded.hostname,os=excluded.os,status='ONLINE',last_heartbeat_at=now(),updated_at=now() returning id",
                projectId, actor.sub(), displayName, name, hostname, os);
        activity(projectId, id, null, null, "WORKSPACE_CONNECTED", "Workspace connected", id);
        return id;
    }

    @Transactional
    public void heartbeatWorkspace(UUID id) {
        ownWorkspace(id);
        db.update("update workspace set last_heartbeat_at=now(),status='ONLINE',updated_at=now() where id=?", id);
    }

    @Transactional
    public UUID registerOrchestrator(UUID workspaceId, String name, String type, String model) {
        ownWorkspace(workspaceId);
        UUID id = uuid("insert into orchestrator(workspace_id,name,type,model) values(?,?,?,?) " +
                "on conflict(workspace_id,name) do update set type=excluded.type,model=excluded.model,status='ACTIVE',last_heartbeat_at=now(),updated_at=now() returning id",
                workspaceId, name, type, model);
        activity(workspaceProject(workspaceId), workspaceId, null, null, "ORCHESTRATOR_STATUS_CHANGED", "Orchestrator active", id);
        return id;
    }

    @Transactional
    public void heartbeatOrchestrator(UUID id) {
        UUID workspaceId = uuid("select workspace_id from orchestrator where id=?", id);
        ownWorkspace(workspaceId);
        db.update("update orchestrator set last_heartbeat_at=now(),status='ACTIVE',updated_at=now() where id=?", id);
    }

    @Transactional
    public UUID registerAgent(UUID orchestratorId, String externalId, String name, String role, String model) {
        UUID workspaceId = uuid("select workspace_id from orchestrator where id=?", orchestratorId);
        ownWorkspace(workspaceId);
        UUID id = uuid("insert into agent(orchestrator_id,external_id,name,role,model) values(?,?,?,?,?) " +
                "on conflict(orchestrator_id,external_id) do update set name=excluded.name,role=excluded.role,model=excluded.model,status=case when agent.status='OFFLINE' then 'IDLE' else agent.status end,last_heartbeat_at=now(),updated_at=now() returning id",
                orchestratorId, externalId, name, role, model);
        activity(workspaceProject(workspaceId), workspaceId, id, null, "AGENT_REGISTERED", "Agent registered", id);
        return id;
    }

    @Transactional
    public void heartbeatAgent(UUID id) {
        ownAgent(id);
        db.update("update agent set last_heartbeat_at=now(),status=case when status='OFFLINE' then 'IDLE' else status end,updated_at=now() where id=?", id);
    }

    @Transactional
    public void heartbeatAgentTree(UUID id) {
        ownAgent(id);
        UUID orchestratorId = uuid("select orchestrator_id from agent where id=?", id);
        UUID workspaceId = agentWorkspace(id);
        heartbeatWorkspace(workspaceId);
        heartbeatOrchestrator(orchestratorId);
        heartbeatAgent(id);
    }

    @Transactional
    public void setAgentStatus(UUID id, String status) {
        ownAgent(id);
        if (!List.of("IDLE","BUSY","WAITING","BLOCKED","ERROR").contains(status)) throw ApiProblem.badRequest("Invalid agent status");
        db.update("update agent set status=?,updated_at=now() where id=?", status, id);
        UUID workspaceId = agentWorkspace(id);
        activity(workspaceProject(workspaceId), workspaceId, id, null, "AGENT_STATUS_CHANGED", "Agent is " + status, id);
    }

    @Transactional
    public UUID createTask(UUID projectId, UUID parentTaskId, String title, String description) {
        member(projectId);
        if (parentTaskId != null && !taskProject(parentTaskId).equals(projectId)) throw ApiProblem.badRequest("Parent task is in another project");
        UUID id = uuid("insert into task(project_id,parent_task_id,title,description,status,created_by) values(?,?,?,?,'READY',?) returning id",
                projectId, parentTaskId, title, description, actor.sub());
        activity(projectId, null, null, id, "TASK_CREATED", "Task created", id);
        return id;
    }

    @Transactional
    public void addDependency(UUID taskId, UUID dependencyId) {
        UUID projectId = taskProject(taskId);
        member(projectId);
        String status = one("select status from task where id=? for update", taskId);
        if (!status.equals("READY") && !status.equals("BACKLOG")) throw ApiProblem.conflict("Dependencies cannot change after a task is claimed");
        if (!taskProject(dependencyId).equals(projectId)) throw ApiProblem.badRequest("Dependency is in another project");
        Integer cycle = db.queryForObject("with recursive chain(id) as (select depends_on_task_id from task_dependency where task_id=? union select d.depends_on_task_id from task_dependency d join chain c on d.task_id=c.id) select count(*) from chain where id=?", Integer.class, dependencyId, taskId);
        if (taskId.equals(dependencyId) || (cycle != null && cycle > 0)) throw ApiProblem.conflict("Dependency cycle");
        db.update("insert into task_dependency(task_id,depends_on_task_id) values(?,?) on conflict do nothing", taskId, dependencyId);
    }

    @Transactional
    public void claim(UUID taskId, UUID workspaceId, UUID agentId) {
        UUID projectId = taskProject(taskId);
        ownWorkspace(workspaceId);
        if (!workspaceProject(workspaceId).equals(projectId) || !agentWorkspace(agentId).equals(workspaceId)) throw ApiProblem.forbidden("Agent or workspace is outside task project");
        int agentChanged = db.update("update agent set current_task_id=?,status='BUSY',updated_at=now() where id=? and current_task_id is null", taskId, agentId);
        if (agentChanged != 1) throw ApiProblem.conflict("Agent already has a task");
        int changed = db.update("update task set status='CLAIMED',owner_workspace_id=?,executor_agent_id=?,updated_at=now() " +
                "where id=? and status='READY' and (owner_workspace_id is null or owner_workspace_id=?) and not exists(select 1 from task_dependency d join task prerequisite on prerequisite.id=d.depends_on_task_id where d.task_id=? and prerequisite.status<>'COMPLETED')",
                workspaceId, agentId, taskId, workspaceId, taskId);
        if (changed != 1) throw ApiProblem.conflict("Task is unavailable or dependencies are incomplete");
        activity(projectId, workspaceId, agentId, taskId, "TASK_CLAIMED", "Task claimed", taskId);
    }

    @Transactional
    public void transitionTask(UUID taskId, String expected, String target) {
        UUID projectId = taskProject(taskId);
        member(projectId);
        UUID owner = uuid("select owner_workspace_id from task where id=?", taskId);
        ownWorkspace(owner);
        int changed = db.update("update task set status=?,started_at=case when ?='IN_PROGRESS' then now() else started_at end,completed_at=case when ?='COMPLETED' then now() else completed_at end,updated_at=now() where id=? and status=?",
                target, target, target, taskId, expected);
        if (changed != 1) throw ApiProblem.conflict("Invalid task transition");
        UUID agentId = uuid("select executor_agent_id from task where id=?", taskId);
        if (target.equals("COMPLETED")) db.update("update agent set current_task_id=null,status='IDLE',updated_at=now() where id=?", agentId);
        activity(projectId, owner, agentId, taskId, "TASK_" + target, "Task " + target.toLowerCase(), taskId);
    }

    public String organization(UUID id) {
        Integer visible = db.queryForObject("select count(*) from organization o where o.id=? and (o.owner_sub=? or exists(select 1 from project p join project_member pm on pm.project_id=p.id where p.organization_id=o.id and pm.user_sub=?))", Integer.class, id, actor.sub(), actor.sub());
        if (visible == null || visible == 0) throw ApiProblem.forbidden("Not an organization member");
        return one("select row_to_json(x)::text from (select * from organization where id=?) x", id);
    }
    public String organizations() {
        String user = actor.sub();
        return many("select row_to_json(x)::text from (select o.* from organization o where o.owner_sub=? " +
                "or exists(select 1 from project p join project_member pm on pm.project_id=p.id " +
                "where p.organization_id=o.id and pm.user_sub=?) order by o.created_at desc) x", user, user);
    }
    public String organizationProjects(UUID organizationId) {
        organization(organizationId);
        String user = actor.sub();
        return many("select row_to_json(x)::text from (select p.* from project p join project_member pm " +
                "on pm.project_id=p.id where p.organization_id=? and pm.user_sub=? " +
                "order by p.created_at desc) x", organizationId, user);
    }
    public String projects() {
        return many("select row_to_json(x)::text from (select p.* from project p join project_member pm " +
                "on pm.project_id=p.id where pm.user_sub=? order by p.created_at desc) x", actor.sub());
    }
    public String members(UUID projectId) {
        member(projectId);
        return many("select row_to_json(x)::text from (select user_sub,display_name,email,invitation_status,role,created_at " +
                "from project_member where project_id=? order by created_at) x", projectId);
    }
    public String orchestrators(UUID projectId) {
        member(projectId);
        return many("select row_to_json(x)::text from (select o.* from orchestrator o join workspace w " +
                "on w.id=o.workspace_id where w.project_id=? order by o.created_at) x", projectId);
    }
    public String project(UUID id) { member(id); return one("select row_to_json(x)::text from (select * from project where id=?) x", id); }
    public String workspace(UUID id) { member(workspaceProject(id)); return one("select row_to_json(x)::text from (select * from workspace where id=?) x", id); }
    public String orchestrator(UUID id) { UUID wid = uuid("select workspace_id from orchestrator where id=?", id); member(workspaceProject(wid)); return one("select row_to_json(x)::text from (select * from orchestrator where id=?) x", id); }
    public String agent(UUID id) { member(workspaceProject(agentWorkspace(id))); return one("select row_to_json(x)::text from (select * from agent where id=?) x", id); }
    public String task(UUID id) { member(taskProject(id)); return one("select row_to_json(x)::text from (select * from task where id=?) x", id); }
    public String workspaces(UUID projectId) { member(projectId); return many("select row_to_json(x)::text from (select * from workspace where project_id=? order by created_at) x", projectId); }
    public String agents(UUID projectId) { member(projectId); return many("select row_to_json(x)::text from (select a.* from agent a join orchestrator o on o.id=a.orchestrator_id join workspace w on w.id=o.workspace_id where w.project_id=? order by a.created_at) x", projectId); }
    public String tasks(UUID projectId) { member(projectId); return many("select row_to_json(x)::text from (select * from task where project_id=? order by created_at) x", projectId); }
}
