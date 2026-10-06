package dev.agentorchestrator.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.agentorchestrator.control.security.McpAccessTokens;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpTokenSecurityTest {
    @Container static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.security.public-base-url", () -> "https://example.test");
    }

    @Value("${local.server.port}") int port;
    @Autowired McpAccessTokens tokens;
    @MockitoBean JwtDecoder decoder;

    @Test void personalCredentialAuthenticatesOnlyMcpAndCanBeRevoked() throws Exception {
        Map<String, Object> created = tokens.create("claude-user", "Claude test");
        String token = created.get("token").toString();
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"Claude\",\"version\":\"1\"}}}";
        HttpClient http = HttpClient.newHttpClient();
        URI mcp = URI.create("http://127.0.0.1:" + port + "/mcp");
        HttpRequest authorized = HttpRequest.newBuilder(mcp).header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream").header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> result = http.send(authorized, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertTrue(result.body().contains("agent-orchestrator"));
        HttpRequest noToken = HttpRequest.newBuilder(mcp).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        assertEquals(401, http.send(noToken, HttpResponse.BodyHandlers.ofString()).statusCode());
        HttpRequest rest = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/organizations"))
                .header("Authorization", "Bearer " + token).GET().build();
        assertEquals(401, http.send(rest, HttpResponse.BodyHandlers.ofString()).statusCode());
        tokens.revoke("claude-user", UUID.fromString(created.get("id").toString()));
        assertEquals(401, http.send(authorized, HttpResponse.BodyHandlers.ofString()).statusCode());
    }
}
