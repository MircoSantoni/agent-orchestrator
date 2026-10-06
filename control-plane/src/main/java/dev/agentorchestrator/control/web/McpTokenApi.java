package dev.agentorchestrator.control.web;

import dev.agentorchestrator.control.security.Actor;
import dev.agentorchestrator.control.security.McpAccessTokens;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/mcp-tokens")
public class McpTokenApi {
    public record CreateToken(@NotBlank @Size(max=80) String name) {}
    private final Actor actor;
    private final McpAccessTokens tokens;
    public McpTokenApi(Actor actor, McpAccessTokens tokens) { this.actor = actor; this.tokens = tokens; }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateToken input) {
        actor.requireHuman();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tokens.create(actor.sub(), input.name()));
    }

    @GetMapping
    public List<McpAccessTokens.TokenInfo> list() {
        actor.requireHuman();
        return tokens.list(actor.sub());
    }

    @DeleteMapping("/{id}")
    public Map<String, String> revoke(@PathVariable UUID id) {
        actor.requireHuman();
        tokens.revoke(actor.sub(), id);
        return Map.of("status", "REVOKED");
    }
}
