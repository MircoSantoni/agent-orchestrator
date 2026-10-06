package dev.agentorchestrator.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.agentorchestrator.control.security.Actor;
import dev.agentorchestrator.control.security.McpAccessTokens;
import dev.agentorchestrator.control.web.ApiProblem;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class ActorTest {
    private final Actor actor = new Actor(new MockHttpServletRequest(), "human", "bridge", "mcp",
            "agent-orchestrator/api", "https://api.example.com", new MockEnvironment());

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    private void authenticate(String client, String scope, String audience, String tokenUse) {
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "none").subject("member-1")
                .claim("client_id", client).claim("scope", scope).claim("aud", List.of(audience))
                .claim("token_use", tokenUse).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @Test void acceptsBoundScopedHumanToken() {
        authenticate("human", "openid agent-orchestrator/api", "https://api.example.com", "access");
        assertEquals("member-1", actor.sub());
        actor.requireHuman();
    }

    @Test void rejectsWrongAudienceAndScopeAndClient() {
        authenticate("human", "agent-orchestrator/api", "https://other.example.com", "access");
        assertThrows(ApiProblem.class, actor::sub);
        authenticate("human", "openid", "https://api.example.com", "access");
        assertThrows(ApiProblem.class, actor::sub);
        authenticate("unknown", "agent-orchestrator/api", "https://api.example.com", "access");
        assertThrows(ApiProblem.class, actor::sub);
    }

    @Test void mcpAndBridgeTokensCannotApprove() {
        for (String client : List.of("mcp", "bridge")) {
            authenticate(client, "agent-orchestrator/api", "https://api.example.com", "access");
            assertEquals("member-1", actor.sub());
            assertThrows(ApiProblem.class, actor::requireHuman);
        }
    }

    @Test void personalMcpCredentialCannotApprove() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new McpAccessTokens.Identity("member-1"), null, List.of()));
        assertEquals("member-1", actor.sub());
        assertThrows(ApiProblem.class, actor::requireHuman);
    }
}
