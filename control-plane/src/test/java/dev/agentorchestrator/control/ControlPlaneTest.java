package dev.agentorchestrator.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import dev.agentorchestrator.control.events.MaintenanceJobs;

@Testcontainers
@ActiveProfiles("dev")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ControlPlaneTest {
    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate db;
    @Autowired MaintenanceJobs jobs;
    RestClient client;
    String projectId;
    String mircoWorkspace;
    String juanWorkspace;
    String mircoAgent;
    String juanAgent;

    @BeforeEach
    void setup() {
        client = RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String org = id(post("mirco", "/organizations", Map.of("name", "Test", "slug", "org-" + suffix)));
        projectId = id(post("mirco", "/organizations/" + org + "/projects", Map.of("name", "Test", "slug", "project-" + suffix)));
        post("mirco", "/projects/" + projectId + "/members", Map.of("userSub", "juan", "displayName", "Juan"));
        mircoWorkspace = id(post("mirco", "/projects/" + projectId + "/workspaces", workspace("mirco")));
        juanWorkspace = id(post("juan", "/projects/" + projectId + "/workspaces", workspace("juan")));
        String mo = id(post("mirco", "/workspaces/" + mircoWorkspace + "/orchestrators", Map.of("name", "sim", "type", "SIMULATED")));
        String jo = id(post("juan", "/workspaces/" + juanWorkspace + "/orchestrators", Map.of("name", "sim", "type", "SIMULATED")));
        mircoAgent = id(post("mirco", "/orchestrators/" + mo + "/agents", Map.of("externalId", "backend", "name", "backend")));
        juanAgent = id(post("juan", "/orchestrators/" + jo + "/agents", Map.of("externalId", "backend", "name", "backend")));
    }

    private Map<String, String> workspace(String owner) {
        return Map.of("name", owner + "-pc", "hostname", owner + "-host", "ownerDisplayName", owner);
    }

    private String id(Map<?, ?> map) { return map.get("id").toString(); }

    private Map<?, ?> post(String user, String path, Object body) {
        return client.post().uri("/api/v1" + path).header("X-Dev-User", user)
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(Map.class);
    }

    private Map<?, ?> get(String user, String path) {
        return client.get().uri("/api/v1" + path).header("X-Dev-User", user).retrieve().body(Map.class);
    }

    @Test
    void onlyOneAgentCanClaimReadyTask() throws Exception {
        String task = id(post("mirco", "/projects/" + projectId + "/tasks", Map.of("title", "Concurrent claim")));
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Integer> a = executor.submit(() -> claimAfter(start, "mirco", task, mircoWorkspace, mircoAgent));
            Future<Integer> b = executor.submit(() -> claimAfter(start, "juan", task, juanWorkspace, juanAgent));
            start.countDown();
            assertEquals(201, a.get() + b.get()); // one 200, one 409
        }
    }

    private int claimAfter(CountDownLatch start, String user, String task, String workspace, String agent) throws Exception {
        start.await();
        try {
            post(user, "/tasks/" + task + "/claim", Map.of("workspaceId", workspace, "agentId", agent));
            return 200;
        } catch (RestClientResponseException e) {
            assertEquals(409, e.getStatusCode().value());
            return 1;
        }
    }

    @Test
    void writeConflictProducesAuditableEvent() {
        post("mirco", "/projects/" + projectId + "/resource-intents", intent(mircoWorkspace, mircoAgent, "WRITE"));
        post("juan", "/projects/" + projectId + "/resource-intents", intent(juanWorkspace, juanAgent, "WRITE"));
        List<?> events = client.get().uri("/api/v1/projects/" + projectId + "/activity")
                .header("X-Dev-User", "mirco").retrieve().body(List.class);
        assertTrue(events.stream().map(Map.class::cast).anyMatch(e -> "RESOURCE_CONFLICT_DETECTED".equals(e.get("type"))
                && "HIGH".equals(((Map<?, ?>)e.get("payload")).get("severity"))));
    }

    private Map<String, Object> intent(String workspace, String agent, String type) {
        return Map.of("workspaceId", workspace, "agentId", agent, "resourceType", "FILE",
                "resourcePath", "src/SecurityConfig.java", "intentType", type, "leaseSeconds", 600);
    }

    @Test
    void proposalNeedsHumanApprovalAndHandoffCreatesChildTask() {
        String parent = id(post("mirco", "/projects/" + projectId + "/tasks", Map.of("title", "Backend")));
        String proposal = id(post("mirco", "/projects/" + projectId + "/context",
                Map.of("type", "PROPOSAL", "title", "JWT location", "content", "Use gateway", "workspaceId", mircoWorkspace)));
        List<?> entries = client.get().uri("/api/v1/projects/" + projectId + "/context")
                .header("X-Dev-User", "juan").retrieve().body(List.class);
        assertTrue(entries.stream().map(e -> (Map<?, ?>) e).anyMatch(e ->
                proposal.equals(e.get("id")) && "PENDING_APPROVAL".equals(e.get("status"))));
        Map<?, ?> decision = post("juan", "/context/" + proposal + "/approve", Map.of());
        assertEquals("DECISION", decision.get("type"));
        String handoff = id(post("mirco", "/projects/" + projectId + "/messages", Map.of(
                "fromWorkspaceId", mircoWorkspace, "toWorkspaceId", juanWorkspace, "taskId", parent,
                "type", "TASK_HANDOFF", "subject", "Frontend", "body", "Integrate API")));
        post("juan", "/messages/" + handoff + "/ack", Map.of());
        Map<?, ?> accepted = post("juan", "/messages/" + handoff + "/accept-handoff", Map.of());
        String child = accepted.get("taskId").toString();
        Map<?, ?> childTask = get("juan", "/tasks/" + child);
        assertEquals(parent, childTask.get("parent_task_id"));
        assertEquals(juanWorkspace, childTask.get("owner_workspace_id"));
        assertNotEquals(parent, child);
    }

    @Test
    void expiredIntentAndOfflineWorkspaceRecoverWithoutDeletingHistory() {
        String intent = id(post("mirco", "/projects/" + projectId + "/resource-intents", intent(mircoWorkspace, mircoAgent, "WRITE")));
        db.update("update resource_intent set lease_until=now()-interval '1 second' where id=?", UUID.fromString(intent));
        jobs.expireIntents();
        assertEquals("EXPIRED", db.queryForObject("select status from resource_intent where id=?", String.class, UUID.fromString(intent)));

        db.update("update workspace set last_heartbeat_at=now()-interval '60 seconds' where id=?", UUID.fromString(mircoWorkspace));
        jobs.markOffline();
        assertEquals("OFFLINE", db.queryForObject("select status from workspace where id=?", String.class, UUID.fromString(mircoWorkspace)));
        post("mirco", "/workspaces/" + mircoWorkspace + "/heartbeat", Map.of());
        assertEquals("ONLINE", db.queryForObject("select status from workspace where id=?", String.class, UUID.fromString(mircoWorkspace)));
    }
}
