package dev.agentorchestrator.control.web;

import dev.agentorchestrator.control.data.Store;
import dev.agentorchestrator.control.identity.InvitationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class CoreApi {
    private final Store store;
    private final InvitationService invitations;
    public CoreApi(Store store, InvitationService invitations) { this.store = store; this.invitations = invitations; }

    public record OrganizationInput(@NotBlank String name, @NotBlank String slug) {}
    public record ProjectInput(@NotBlank String name, @NotBlank String slug) {}
    public record InvitationInput(@NotBlank @Email String email, @NotBlank String displayName) {}
    public record ResendInput(@NotBlank @Email String email) {}
    public record WorkspaceInput(@NotBlank String name, @NotBlank String hostname, String os, @NotBlank String ownerDisplayName) {}
    public record OrchestratorInput(@NotBlank String name, @NotBlank String type, String model) {}
    public record AgentInput(@NotBlank String externalId, @NotBlank String name, String role, String model) {}
    public record StatusInput(@NotBlank String status) {}
    public record TaskInput(UUID parentTaskId, @NotBlank String title, String description) {}
    public record ClaimInput(@NotNull UUID workspaceId, @NotNull UUID agentId) {}
    public record DependencyInput(@NotNull UUID dependsOnTaskId) {}

    @PostMapping(value="/organizations", produces=MediaType.APPLICATION_JSON_VALUE)
    public String organization(@Valid @RequestBody OrganizationInput input) {
        return store.organization(store.createOrganization(input.name(), input.slug()));
    }

    @GetMapping(value="/me", produces=MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> me() { return Map.of("sub", store.currentUser()); }

    @PostMapping("/me/activate")
    public Map<String, String> activate() { store.markCurrentUserActive(); return Map.of("status", "ACTIVE"); }

    @GetMapping(value="/organizations", produces=MediaType.APPLICATION_JSON_VALUE)
    public String organizations() { return store.organizations(); }

    @GetMapping(value="/organizations/{organizationId}/projects", produces=MediaType.APPLICATION_JSON_VALUE)
    public String organizationProjects(@PathVariable UUID organizationId) { return store.organizationProjects(organizationId); }

    @PostMapping(value="/organizations/{organizationId}/projects", produces=MediaType.APPLICATION_JSON_VALUE)
    public String project(@PathVariable UUID organizationId, @Valid @RequestBody ProjectInput input) {
        return store.project(store.createProject(organizationId, input.name(), input.slug()));
    }

    @GetMapping(value="/projects/{projectId}", produces=MediaType.APPLICATION_JSON_VALUE)
    public String project(@PathVariable UUID projectId) { return store.project(projectId); }

    @GetMapping(value="/projects/{projectId}/members", produces=MediaType.APPLICATION_JSON_VALUE)
    public String members(@PathVariable UUID projectId) { return store.members(projectId); }

    @PostMapping("/projects/{projectId}/invitations")
    public Map<String, String> invite(@PathVariable UUID projectId, @Valid @RequestBody InvitationInput input) {
        return invitations.invite(projectId, input.email(), input.displayName());
    }

    @PostMapping("/projects/{projectId}/invitations/resend")
    public Map<String, String> resend(@PathVariable UUID projectId, @Valid @RequestBody ResendInput input) {
        return invitations.resend(projectId, input.email());
    }

    @PostMapping(value="/projects/{projectId}/workspaces", produces=MediaType.APPLICATION_JSON_VALUE)
    public String workspace(@PathVariable UUID projectId, @Valid @RequestBody WorkspaceInput input) {
        return store.workspace(store.registerWorkspace(projectId, input.name(), input.hostname(), input.os(), input.ownerDisplayName()));
    }

    @PostMapping("/workspaces/{id}/heartbeat")
    public Map<String, String> heartbeatWorkspace(@PathVariable UUID id) { store.heartbeatWorkspace(id); return Map.of("status", "ONLINE"); }

    @GetMapping(value="/projects/{projectId}/workspaces", produces=MediaType.APPLICATION_JSON_VALUE)
    public String workspaces(@PathVariable UUID projectId) { return store.workspaces(projectId); }

    @PostMapping(value="/workspaces/{workspaceId}/orchestrators", produces=MediaType.APPLICATION_JSON_VALUE)
    public String orchestrator(@PathVariable UUID workspaceId, @Valid @RequestBody OrchestratorInput input) {
        return store.orchestrator(store.registerOrchestrator(workspaceId, input.name(), input.type(), input.model()));
    }

    @PostMapping("/orchestrators/{id}/heartbeat")
    public Map<String, String> heartbeatOrchestrator(@PathVariable UUID id) { store.heartbeatOrchestrator(id); return Map.of("status", "ACTIVE"); }

    @PostMapping(value="/orchestrators/{orchestratorId}/agents", produces=MediaType.APPLICATION_JSON_VALUE)
    public String agent(@PathVariable UUID orchestratorId, @Valid @RequestBody AgentInput input) {
        return store.agent(store.registerAgent(orchestratorId, input.externalId(), input.name(), input.role(), input.model()));
    }

    @PostMapping("/agents/{id}/heartbeat")
    public Map<String, String> heartbeatAgent(@PathVariable UUID id) { store.heartbeatAgent(id); return Map.of("status", "OK"); }

    @PatchMapping("/agents/{id}/status")
    public Map<String, String> agentStatus(@PathVariable UUID id, @Valid @RequestBody StatusInput input) {
        store.setAgentStatus(id, input.status()); return Map.of("status", input.status());
    }

    @GetMapping(value="/projects/{projectId}/agents", produces=MediaType.APPLICATION_JSON_VALUE)
    public String agents(@PathVariable UUID projectId) { return store.agents(projectId); }

    @GetMapping(value="/projects/{projectId}/orchestrators", produces=MediaType.APPLICATION_JSON_VALUE)
    public String orchestrators(@PathVariable UUID projectId) { return store.orchestrators(projectId); }

    @PostMapping(value="/projects/{projectId}/tasks", produces=MediaType.APPLICATION_JSON_VALUE)
    public String task(@PathVariable UUID projectId, @Valid @RequestBody TaskInput input) {
        return store.task(store.createTask(projectId, input.parentTaskId(), input.title(), input.description() == null ? "" : input.description()));
    }

    @GetMapping(value="/projects/{projectId}/tasks", produces=MediaType.APPLICATION_JSON_VALUE)
    public String tasks(@PathVariable UUID projectId) { return store.tasks(projectId); }

    @GetMapping(value="/tasks/{id}", produces=MediaType.APPLICATION_JSON_VALUE)
    public String task(@PathVariable UUID id) { return store.task(id); }

    @PostMapping("/tasks/{id}/dependencies")
    public Map<String, String> dependency(@PathVariable UUID id, @Valid @RequestBody DependencyInput input) {
        store.addDependency(id, input.dependsOnTaskId()); return Map.of("status", "CREATED");
    }

    @PostMapping("/tasks/{id}/claim")
    public Map<String, String> claim(@PathVariable UUID id, @Valid @RequestBody ClaimInput input) {
        store.claim(id, input.workspaceId(), input.agentId()); return Map.of("status", "CLAIMED");
    }

    @PostMapping("/tasks/{id}/start")
    public Map<String, String> start(@PathVariable UUID id) { store.transitionTask(id, "CLAIMED", "IN_PROGRESS"); return Map.of("status", "IN_PROGRESS"); }

    @PostMapping("/tasks/{id}/complete")
    public Map<String, String> complete(@PathVariable UUID id) { store.transitionTask(id, "IN_PROGRESS", "COMPLETED"); return Map.of("status", "COMPLETED"); }

    @PostMapping("/tasks/{id}/block")
    public Map<String, String> block(@PathVariable UUID id) { store.transitionTask(id, "IN_PROGRESS", "BLOCKED"); return Map.of("status", "BLOCKED"); }
}
