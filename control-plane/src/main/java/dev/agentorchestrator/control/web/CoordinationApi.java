package dev.agentorchestrator.control.web;

import dev.agentorchestrator.control.data.CoordinationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class CoordinationApi {
    private final CoordinationService service;
    public CoordinationApi(CoordinationService service) { this.service = service; }

    public record IntentInput(@NotNull UUID workspaceId, @NotNull UUID agentId, UUID taskId,
                              @NotBlank String resourceType, @NotBlank String resourcePath,
                              @NotBlank String intentType, int leaseSeconds) {}
    public record RenewInput(int leaseSeconds) {}
    public record ContextInput(UUID workspaceId, UUID agentId, UUID taskId,
                               @NotBlank String type, @NotBlank String title, @NotBlank String content) {}
    public record MessageInput(@NotNull UUID fromWorkspaceId, UUID fromAgentId, @NotNull UUID toWorkspaceId,
                               UUID toAgentId, UUID taskId, @NotBlank String type, String subject, @NotBlank String body) {}

    @PostMapping(value="/projects/{projectId}/resource-intents", produces=MediaType.APPLICATION_JSON_VALUE)
    public String intent(@PathVariable UUID projectId, @Valid @RequestBody IntentInput input) {
        UUID id = service.createIntent(projectId, input.workspaceId(), input.agentId(), input.taskId(), input.resourceType(), input.resourcePath(), input.intentType(), input.leaseSeconds());
        return "{\"id\":\"" + id + "\"}";
    }

    @PostMapping("/resource-intents/{id}/renew")
    public Map<String, String> renew(@PathVariable UUID id, @RequestBody RenewInput input) {
        service.renewIntent(id, input.leaseSeconds()); return Map.of("status", "ACTIVE");
    }

    @DeleteMapping("/resource-intents/{id}")
    public Map<String, String> release(@PathVariable UUID id) { service.releaseIntent(id); return Map.of("status", "RELEASED"); }

    @GetMapping(value="/projects/{projectId}/resource-intents", produces=MediaType.APPLICATION_JSON_VALUE)
    public String intents(@PathVariable UUID projectId) { return service.intents(projectId); }

    @PostMapping(value="/projects/{projectId}/context", produces=MediaType.APPLICATION_JSON_VALUE)
    public String context(@PathVariable UUID projectId, @Valid @RequestBody ContextInput input) {
        return service.contextEntry(service.createContext(projectId, input.workspaceId(), input.agentId(), input.taskId(), input.type(), input.title(), input.content()));
    }

    @GetMapping(value="/projects/{projectId}/context", produces=MediaType.APPLICATION_JSON_VALUE)
    public String context(@PathVariable UUID projectId) { return service.context(projectId); }

    @PostMapping(value="/context/{id}/approve", produces=MediaType.APPLICATION_JSON_VALUE)
    public String approve(@PathVariable UUID id) { return service.contextEntry(service.approve(id)); }

    @PostMapping("/context/{id}/reject")
    public Map<String, String> reject(@PathVariable UUID id) { service.reject(id); return Map.of("status", "REJECTED"); }

    @PostMapping(value="/projects/{projectId}/messages", produces=MediaType.APPLICATION_JSON_VALUE)
    public String message(@PathVariable UUID projectId, @Valid @RequestBody MessageInput input) {
        return service.message(service.sendMessage(projectId, input.fromWorkspaceId(), input.fromAgentId(), input.toWorkspaceId(), input.toAgentId(), input.taskId(), input.type(), input.subject(), input.body()));
    }

    @GetMapping(value="/projects/{projectId}/message-flow", produces=MediaType.APPLICATION_JSON_VALUE)
    public String messageFlow(@PathVariable UUID projectId) { return service.messageFlow(projectId); }

    @GetMapping(value="/projects/{projectId}/inbox", produces=MediaType.APPLICATION_JSON_VALUE)
    public String projectInbox(@PathVariable UUID projectId) { return service.projectInbox(projectId); }

    @GetMapping(value="/workspaces/{workspaceId}/messages", produces=MediaType.APPLICATION_JSON_VALUE)
    public String inbox(@PathVariable UUID workspaceId) { return service.inbox(workspaceId); }

    @PostMapping("/messages/{id}/read")
    public Map<String, String> read(@PathVariable UUID id) { service.read(id); return Map.of("status", "READ"); }

    @PostMapping("/messages/{id}/ack")
    public Map<String, String> ack(@PathVariable UUID id) { service.acknowledge(id); return Map.of("status", "ACKNOWLEDGED"); }

    @PostMapping(value="/messages/{id}/accept-handoff", produces=MediaType.APPLICATION_JSON_VALUE)
    public String handoff(@PathVariable UUID id) { return "{\"taskId\":\"" + service.acceptHandoff(id) + "\"}"; }
}
