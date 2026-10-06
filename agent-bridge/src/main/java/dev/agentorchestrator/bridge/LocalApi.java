package dev.agentorchestrator.bridge;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/local")
public class LocalApi {
    private final BridgeClient client;
    public LocalApi(BridgeClient client) { this.client = client; }

    public record AgentInput(@NotBlank String externalId, @NotBlank String name, @NotBlank String role) {}
    public record StatusInput(@NotBlank String status) {}
    public record ClaimInput(@NotBlank String agentId) {}
    public record IntentInput(@NotBlank String agentId, String taskId, @NotBlank String resourceType,
                              @NotBlank String resourcePath, @NotBlank String intentType, int leaseSeconds) {}
    public record MessageInput(String fromAgentId, @NotBlank String toWorkspaceId, String toAgentId,
                               String taskId, @NotBlank String type, String subject, @NotBlank String body) {}

    @GetMapping("/state")
    public Map<String, Object> state() { return client.state(); }

    @GetMapping("/events")
    public String[] events() { return client.events(); }

    @PostMapping("/agents")
    public Map<?, ?> agent(@Valid @RequestBody AgentInput input) {
        return client.registerAgent(input.externalId(), input.name(), input.role());
    }

    @PatchMapping("/agents/{id}/status")
    public Map<?, ?> status(@PathVariable String id, @Valid @RequestBody StatusInput input) { return client.setStatus(id, input.status()); }

    @PostMapping("/tasks/{id}/claim")
    public Map<?, ?> claim(@PathVariable String id, @Valid @RequestBody ClaimInput input) { return client.claim(id, input.agentId()); }

    @PostMapping("/tasks/{id}/start")
    public Map<?, ?> start(@PathVariable String id) { return client.start(id); }

    @PostMapping("/tasks/{id}/complete")
    public Map<?, ?> complete(@PathVariable String id) { return client.complete(id); }

    @PostMapping("/resource-intents")
    public Map<?, ?> intent(@Valid @RequestBody IntentInput input) {
        return client.intent(input.agentId(), input.taskId(), input.resourceType(), input.resourcePath(), input.intentType(), input.leaseSeconds());
    }

    @PostMapping("/messages")
    public Map<?, ?> message(@Valid @RequestBody MessageInput input) {
        return client.message(input.fromAgentId(), input.toWorkspaceId(), input.toAgentId(), input.taskId(), input.type(), input.subject(), input.body());
    }
}
