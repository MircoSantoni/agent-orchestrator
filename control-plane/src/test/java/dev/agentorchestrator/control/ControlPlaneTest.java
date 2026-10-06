package dev.agentorchestrator.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.concurrent.TimeUnit;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import dev.agentorchestrator.control.events.MaintenanceJobs;
import dev.agentorchestrator.control.identity.UserDirectory;

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
    @MockitoBean UserDirectory users;
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
        when(users.findByEmail("juan@example.com"))
                .thenReturn(Optional.of(new UserDirectory.User("juan", "juan@example.com", true)));
        post("mirco", "/projects/" + projectId + "/invitations",
                Map.of("email", "juan@example.com", "displayName", "Juan"));
        reset(users);
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

    private Map<?, ?> mcp(String user, String method, String name, Map<String, Object> arguments) {
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("_meta", Map.of("io.modelcontextprotocol/protocolVersion", "2026-07-28",
                "io.modelcontextprotocol/clientCapabilities", Map.of()));
        if (name != null) { params.put("name", name); params.put("arguments", arguments); }
        var request = client.post().uri("/mcp").header("X-Dev-User", user)
                .header("MCP-Protocol-Version", "2026-07-28").header("Mcp-Method", method)
                .headers(headers -> { if (name != null) headers.add("Mcp-Name", name); })
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .body(Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params));
        return request.retrieve().body(Map.class);
    }

    private String mcpText(String user, String name, Map<String, Object> arguments) {
        Map<?, ?> result = (Map<?, ?>) mcp(user, "tools/call", name, arguments).get("result");
        assertEquals(false, result.get("isError"));
        return (String) ((Map<?, ?>) ((List<?>) result.get("content")).getFirst()).get("text");
    }

    private String jsonField(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker);
        assertTrue(start >= 0, "Missing " + field + " in " + json);
        int valueStart = start + marker.length();
        return json.substring(valueStart, json.indexOf('"', valueStart));
    }

    @Test
    void officialMcpJavaClientCanDiscoverAndCallTools() {
        var transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
                .endpoint("/mcp")
                .requestBuilder(HttpRequest.newBuilder().header("X-Dev-User", "mirco"))
                .build();
        try (var sdk = McpClient.sync(transport).build()) {
            assertEquals("2025-11-25", sdk.initialize().protocolVersion());
            assertTrue(sdk.listTools().tools().stream().anyMatch(t -> "list_tasks".equals(t.name())));
            var result = sdk.callTool(new McpSchema.CallToolRequest("list_tasks", Map.of("projectId", projectId)));
            assertEquals(false, result.isError());
        }
    }

    @Test
    void mcpCoordinatesThroughSameAuthorizationAndKeepsApprovalHuman() {
        Map<?, ?> discovery = (Map<?, ?>) mcp("mirco", "server/discover", null, Map.of()).get("result");
        assertEquals(List.of("2026-07-28"), discovery.get("supportedVersions"));
        Map<?, ?> listed = (Map<?, ?>) mcp("mirco", "tools/list", null, Map.of()).get("result");
        List<?> tools = (List<?>) listed.get("tools");
        assertTrue(tools.stream().map(Map.class::cast).anyMatch(t -> "claim_task".equals(t.get("name"))));
        assertTrue(tools.stream().map(Map.class::cast).noneMatch(t -> "approve_proposal".equals(t.get("name"))));

        String task = id(post("mirco", "/projects/" + projectId + "/tasks", Map.of("title", "MCP task")));
        Map<?, ?> claim = (Map<?, ?>) mcp("mirco", "tools/call", "claim_task", Map.of(
                "taskId", task, "workspaceId", mircoWorkspace, "agentId", mircoAgent)).get("result");
        assertEquals(false, claim.get("isError"));
        assertEquals("CLAIMED", get("mirco", "/tasks/" + task).get("status"));

        Map<?, ?> denied = (Map<?, ?>) mcp("juan", "tools/call", "claim_task", Map.of(
                "taskId", task, "workspaceId", mircoWorkspace, "agentId", juanAgent)).get("result");
        assertEquals(true, denied.get("isError"));
        Map<?, ?> outsider = (Map<?, ?>) mcp("outsider", "tools/call", "list_tasks", Map.of("projectId", projectId)).get("result");
        assertEquals(true, outsider.get("isError"));

        Map<?, ?> proposal = (Map<?, ?>) mcp("mirco", "tools/call", "propose_context", Map.of(
                "projectId", projectId, "workspaceId", mircoWorkspace, "agentId", mircoAgent,
                "title", "Design", "content", "Use an MCP adapter")).get("result");
        assertEquals(false, proposal.get("isError"));
        List<?> context = client.get().uri("/api/v1/projects/" + projectId + "/context")
                .header("X-Dev-User", "mirco").retrieve().body(List.class);
        assertTrue(context.stream().map(Map.class::cast).anyMatch(entry ->
                "Design".equals(entry.get("title")) && "PENDING_APPROVAL".equals(entry.get("status"))));
    }

    @Test
    void remoteMcpAgentCanConnectAndWorkWithoutBridge() {
        assertTrue(mcpText("mirco", "list_projects", Map.of()).contains(projectId));
        Map<?, ?> outsider = (Map<?, ?>) mcp("outsider", "tools/call", "connect_agent", Map.of(
                "projectId", projectId, "workspaceName", "remote", "displayName", "Outsider",
                "agentKey", "audit", "agentName", "Auditor")).get("result");
        assertEquals(true, outsider.get("isError"));

        Map<String, Object> registration = Map.of("projectId", projectId, "workspaceName", "remote",
                "displayName", "Mirco", "agentKey", "audit", "agentName", "Auditor");
        String connected = mcpText("mirco", "connect_agent", registration);
        String workspace = jsonField(connected, "workspaceId");
        String agent = jsonField(connected, "agentId");
        assertEquals(workspace, jsonField(mcpText("mirco", "connect_agent", registration), "workspaceId"));
        assertTrue(mcpText("mirco", "list_workspaces", Map.of("projectId", projectId)).contains(workspace));
        mcpText("mirco", "heartbeat_agent", Map.of("agentId", agent));
        Map<?, ?> unauthorizedHeartbeat = (Map<?, ?>) mcp("juan", "tools/call", "heartbeat_agent",
                Map.of("agentId", agent)).get("result");
        assertEquals(true, unauthorizedHeartbeat.get("isError"));

        String task = id(post("mirco", "/projects/" + projectId + "/tasks", Map.of("title", "Remote MCP task")));
        mcpText("mirco", "claim_task", Map.of("taskId", task, "workspaceId", workspace, "agentId", agent));
        mcpText("mirco", "start_task", Map.of("taskId", task));
        assertEquals("IN_PROGRESS", get("mirco", "/tasks/" + task).get("status"));
        mcpText("mirco", "complete_task", Map.of("taskId", task));
        assertEquals("COMPLETED", get("mirco", "/tasks/" + task).get("status"));

        String message = mcpText("mirco", "send_message", Map.of("projectId", projectId,
                "fromWorkspaceId", workspace, "fromAgentId", agent, "toWorkspaceId", juanWorkspace,
                "type", "COORDINATION_REQUEST", "body", "Buenos días"));
        String messageId = jsonField(message, "id");
        assertTrue(mcpText("juan", "list_inbox", Map.of("workspaceId", juanWorkspace)).contains(messageId));
        mcpText("juan", "read_message", Map.of("messageId", messageId));
        mcpText("juan", "ack_message", Map.of("messageId", messageId));
    }

    @Test
    void legacyMcpClientCanInitializeAndCallTools() {
        Map<?, ?> init = client.post().uri("/mcp").header("X-Dev-User", "mirco")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("jsonrpc", "2.0", "id", 1,
                        "method", "initialize", "params", Map.of("protocolVersion", "2025-11-25",
                                "capabilities", Map.of(), "clientInfo", Map.of("name", "test", "version", "1"))))
                .retrieve().body(Map.class);
        assertEquals("2025-11-25", ((Map<?, ?>) init.get("result")).get("protocolVersion"));
        Map<?, ?> listed = client.post().uri("/mcp").header("X-Dev-User", "mirco")
                .header("MCP-Protocol-Version", "2025-11-25")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("jsonrpc", "2.0", "id", 2,
                        "method", "tools/list", "params", Map.of()))
                .retrieve().body(Map.class);
        assertTrue(((List<?>) ((Map<?, ?>) listed.get("result")).get("tools")).size() > 5);
        Map<?, ?> call = client.post().uri("/mcp").header("X-Dev-User", "mirco")
                .header("MCP-Protocol-Version", "2025-11-25")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("jsonrpc", "2.0", "id", 3,
                        "method", "tools/call", "params", Map.of("name", "list_tasks",
                                "arguments", Map.of("projectId", projectId))))
                .retrieve().body(Map.class);
        assertEquals(false, ((Map<?, ?>) call.get("result")).get("isError"));
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

    @Test
    void agentCannotClaimSecondTaskAndClaimedTaskCannotGainDependencies() {
        String first = id(post("mirco", "/projects/" + projectId + "/tasks", Map.of("title", "First")));
        String second = id(post("mirco", "/projects/" + projectId + "/tasks", Map.of("title", "Second")));
        post("mirco", "/tasks/" + first + "/claim", Map.of("workspaceId", mircoWorkspace, "agentId", mircoAgent));
        assertEquals(409, assertThrows(RestClientResponseException.class, () ->
                post("mirco", "/tasks/" + second + "/claim", Map.of("workspaceId", mircoWorkspace, "agentId", mircoAgent)))
                .getStatusCode().value());
        assertEquals("READY", get("mirco", "/tasks/" + second).get("status"));
        assertEquals(409, assertThrows(RestClientResponseException.class, () ->
                post("mirco", "/tasks/" + first + "/dependencies", Map.of("dependsOnTaskId", second)))
                .getStatusCode().value());
    }

    @Test
    void handoffRequiresOwnershipOfParentTask() {
        String parent = id(post("mirco", "/projects/" + projectId + "/tasks", Map.of("title", "Parent")));
        assertEquals(403, assertThrows(RestClientResponseException.class, () ->
                post("juan", "/projects/" + projectId + "/messages", Map.of(
                        "fromWorkspaceId", juanWorkspace, "toWorkspaceId", mircoWorkspace,
                        "taskId", parent, "type", "TASK_HANDOFF", "body", "Take this")))
                .getStatusCode().value());
    }

    @Test
    void dashboardDiscoveryKeepsProjectsPrivateAndMessageBodiesInRecipientInbox() {
        assertEquals("mirco", get("mirco", "/me").get("sub"));
        String organizationId = get("mirco", "/projects/" + projectId).get("organization_id").toString();
        List<?> organizations = client.get().uri("/api/v1/organizations")
                .header("X-Dev-User", "mirco").retrieve().body(List.class);
        assertTrue(organizations.stream().map(Map.class::cast).anyMatch(o -> organizationId.equals(o.get("id"))));
        List<?> outsiderOrganizations = client.get().uri("/api/v1/organizations")
                .header("X-Dev-User", "outsider").retrieve().body(List.class);
        assertTrue(outsiderOrganizations.stream().map(Map.class::cast).noneMatch(o -> organizationId.equals(o.get("id"))));
        List<?> projects = client.get().uri("/api/v1/organizations/" + organizationId + "/projects")
                .header("X-Dev-User", "juan").retrieve().body(List.class);
        assertTrue(projects.stream().map(Map.class::cast).anyMatch(p -> projectId.equals(p.get("id"))));
        assertEquals(403, assertThrows(RestClientResponseException.class, () ->
                client.get().uri("/api/v1/organizations/" + organizationId + "/projects")
                        .header("X-Dev-User", "outsider").retrieve().body(List.class)).getStatusCode().value());

        post("mirco", "/projects/" + projectId + "/messages", Map.of(
                "fromWorkspaceId", mircoWorkspace, "toWorkspaceId", juanWorkspace,
                "type", "COORDINATION_REQUEST", "body", "Private message body"));
        List<?> flow = client.get().uri("/api/v1/projects/" + projectId + "/message-flow")
                .header("X-Dev-User", "mirco").retrieve().body(List.class);
        assertTrue(flow.stream().map(Map.class::cast).anyMatch(m ->
                juanWorkspace.equals(m.get("to_workspace_id")) && !m.containsKey("body")));
        assertEquals(403, assertThrows(RestClientResponseException.class, () ->
                client.get().uri("/api/v1/workspaces/" + juanWorkspace + "/messages")
                        .header("X-Dev-User", "mirco").retrieve().body(List.class)).getStatusCode().value());
    }

    @Test
    void adminCanInviteByEmailWithoutKnowingCognitoIdAndMemberCanActivate() {
        String email = "new.person@example.com";
        assertEquals(403, assertThrows(RestClientResponseException.class, () ->
                post("juan", "/projects/" + projectId + "/invitations", Map.of(
                        "email", email, "displayName", "New Person"))).getStatusCode().value());
        verifyNoInteractions(users);

        when(users.findByEmail(email)).thenReturn(Optional.empty());
        when(users.createAndInvite(email)).thenReturn(new UserDirectory.User("new-person-sub", email, false));
        Map<?, ?> result = post("mirco", "/projects/" + projectId + "/invitations", Map.of(
                "email", "New.Person@Example.com", "displayName", "New Person"));
        assertEquals("INVITED", result.get("status"));
        List<?> members = client.get().uri("/api/v1/projects/" + projectId + "/members")
                .header("X-Dev-User", "mirco").retrieve().body(List.class);
        assertTrue(members.stream().map(Map.class::cast).anyMatch(m ->
                email.equals(m.get("email")) && "INVITED".equals(m.get("invitation_status"))));

        when(users.findByEmail(email)).thenReturn(Optional.of(new UserDirectory.User("new-person-sub", email, false)));
        Map<?, ?> resent = post("mirco", "/projects/" + projectId + "/invitations/resend", Map.of("email", email));
        assertEquals("RESENT", resent.get("status"));
        verify(users).resendInvitation(email);

        post("new-person-sub", "/me/activate", Map.of());
        List<?> activated = client.get().uri("/api/v1/projects/" + projectId + "/members")
                .header("X-Dev-User", "mirco").retrieve().body(List.class);
        assertTrue(activated.stream().map(Map.class::cast).anyMatch(m ->
                email.equals(m.get("email")) && "ACTIVE".equals(m.get("invitation_status"))));
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

    @Test
    void resourceIntentDetectsDirectoryOverlapWithoutAlertingReadRead() {
        post("mirco", "/projects/" + projectId + "/resource-intents", Map.of(
                "workspaceId", mircoWorkspace, "agentId", mircoAgent, "resourceType", "DIRECTORY",
                "resourcePath", "src/main", "intentType", "READ", "leaseSeconds", 600));
        post("juan", "/projects/" + projectId + "/resource-intents", Map.of(
                "workspaceId", juanWorkspace, "agentId", juanAgent, "resourceType", "FILE",
                "resourcePath", "src/main/App.java", "intentType", "READ", "leaseSeconds", 600));
        List<?> before = client.get().uri("/api/v1/projects/" + projectId + "/activity")
                .header("X-Dev-User", "mirco").retrieve().body(List.class);
        assertTrue(before.stream().map(Map.class::cast).noneMatch(e -> "RESOURCE_CONFLICT_DETECTED".equals(e.get("type"))));
        post("juan", "/projects/" + projectId + "/resource-intents", Map.of(
                "workspaceId", juanWorkspace, "agentId", juanAgent, "resourceType", "FILE",
                "resourcePath", "src/main/Other.java", "intentType", "WRITE", "leaseSeconds", 600));
        List<?> after = client.get().uri("/api/v1/projects/" + projectId + "/activity")
                .header("X-Dev-User", "mirco").retrieve().body(List.class);
        assertTrue(after.stream().map(Map.class::cast).anyMatch(e -> "RESOURCE_CONFLICT_DETECTED".equals(e.get("type"))
                && "WARNING".equals(((Map<?, ?>) e.get("payload")).get("severity"))));
    }

    private Map<String, Object> intent(String workspace, String agent, String type) {
        return Map.of("workspaceId", workspace, "agentId", agent, "resourceType", "FILE",
                "resourcePath", "src/SecurityConfig.java", "intentType", type, "leaseSeconds", 600);
    }

    @Test
    void proposalNeedsHumanApprovalAndHandoffCreatesChildTask() {
        String parent = id(post("mirco", "/projects/" + projectId + "/tasks", Map.of("title", "Backend")));
        post("mirco", "/tasks/" + parent + "/claim", Map.of("workspaceId", mircoWorkspace, "agentId", mircoAgent));
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

    @Test
    void sseReplaysCommittedEventsAfterReconnect() throws Exception {
        String task = id(post("mirco", "/projects/" + projectId + "/tasks", Map.of("title", "Replay task")));
        long taskEventId = db.queryForObject("select max(id) from activity_event where task_id=? and type='TASK_CREATED'",
                Long.class, UUID.fromString(task));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port +
                "/api/v1/projects/" + projectId + "/events"))
                .header("X-Dev-User", "mirco").header("Last-Event-ID", Long.toString(taskEventId - 1))
                .header("Accept", "text/event-stream").GET().build();
        HttpResponse<java.io.InputStream> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        try (var body = response.body(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> event = executor.submit(() -> {
                BufferedReader reader = new BufferedReader(new InputStreamReader(body));
                String line;
                while ((line = reader.readLine()) != null) if (line.startsWith("data:") && line.contains("TASK_CREATED")) return line;
                return "";
            });
            assertTrue(event.get(10, TimeUnit.SECONDS).contains(task));
        }
    }
}
