package dev.agentorchestrator.bridge;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class BridgeClient {
    private static final Logger log = LoggerFactory.getLogger(BridgeClient.class);
    private final String baseUrl;
    private final String projectId;
    private final String ownerId;
    private final String ownerName;
    private final String workspaceName;
    private final String orchestratorName;
    private final String token;
    private final RestClient client;
    private final HttpClient streamClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final AtomicReference<String> workspaceId = new AtomicReference<>();
    private final AtomicReference<String> orchestratorId = new AtomicReference<>();
    private final ArrayDeque<String> recentEvents = new ArrayDeque<>();
    private volatile boolean listening;
    private volatile String lastEventId;

    public BridgeClient(@Value("${bridge.control-plane-url}") String baseUrl,
                        @Value("${bridge.token:}") String token,
                        @Value("${bridge.project-id:}") String projectId,
                        @Value("${bridge.owner-id}") String ownerId,
                        @Value("${bridge.owner-name}") String ownerName,
                        @Value("${bridge.workspace-name}") String workspaceName,
                        @Value("${bridge.orchestrator-name}") String orchestratorName) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.token = token;
        this.projectId = projectId;
        this.ownerId = ownerId;
        this.ownerName = ownerName;
        this.workspaceName = workspaceName;
        this.orchestratorName = orchestratorName;
        RestClient.Builder builder = RestClient.builder().baseUrl(this.baseUrl);
        if (!token.isBlank()) builder.defaultHeader("Authorization", "Bearer " + token);
        else builder.defaultHeader("X-Dev-User", ownerId);
        this.client = builder.build();
    }

    private String id(Map<?, ?> body) { return String.valueOf(body.get("id")); }
    private Map<?, ?> post(String path, Object body) {
        return client.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(Map.class);
    }
    private void heartbeat(String path) { client.post().uri(path).retrieve().toBodilessEntity(); }

    @Scheduled(initialDelay=1000, fixedDelay=15000)
    public void maintain() {
        if (projectId.isBlank()) return;
        try {
            if (workspaceId.get() == null) {
                String hostname = java.net.InetAddress.getLocalHost().getHostName();
                Map<?, ?> workspace = post("/api/v1/projects/" + projectId + "/workspaces",
                        Map.of("name", workspaceName, "hostname", hostname, "os", System.getProperty("os.name"), "ownerDisplayName", ownerName));
                workspaceId.set(id(workspace));
            }
            if (orchestratorId.get() == null) {
                Map<?, ?> orchestrator = post("/api/v1/workspaces/" + workspaceId.get() + "/orchestrators",
                        Map.of("name", orchestratorName, "type", "SIMULATED"));
                orchestratorId.set(id(orchestrator));
            }
            heartbeat("/api/v1/workspaces/" + workspaceId.get() + "/heartbeat");
            heartbeat("/api/v1/orchestrators/" + orchestratorId.get() + "/heartbeat");
            if (!listening) startListener();
        } catch (Exception e) {
            log.warn("Bridge registration/heartbeat failed: {}", e.getMessage());
        }
    }

    private synchronized void startListener() {
        if (listening) return;
        listening = true;
        Thread.ofVirtual().name("bridge-sse").start(this::listenLoop);
    }

    private void listenLoop() {
        while (true) {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/projects/" + projectId + "/events"))
                        .header("Accept", "text/event-stream").GET();
                if (!token.isBlank()) builder.header("Authorization", "Bearer " + token);
                else builder.header("X-Dev-User", ownerId);
                if (lastEventId != null) builder.header("Last-Event-ID", lastEventId);
                HttpResponse<java.io.InputStream> response = streamClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) throw new IllegalStateException("SSE HTTP " + response.statusCode());
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("id:")) lastEventId = line.substring(3).trim();
                        if (line.startsWith("data:")) {
                            String event = line.substring(5).trim();
                            synchronized (recentEvents) {
                                recentEvents.addLast(event);
                                while (recentEvents.size() > 100) recentEvents.removeFirst();
                            }
                            log.info("Control Plane event: {}", event);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("SSE disconnected: {}", e.getMessage());
            }
            try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    public Map<String, Object> state() {
        return Map.of("projectId", projectId, "workspaceId", workspaceId.get() == null ? "" : workspaceId.get(),
                "orchestratorId", orchestratorId.get() == null ? "" : orchestratorId.get(), "ownerId", ownerId,
                "sseConnected", listening);
    }

    public String[] events() { synchronized (recentEvents) { return recentEvents.toArray(String[]::new); } }

    public Map<?, ?> registerAgent(String externalId, String name, String role) {
        requireReady();
        return post("/api/v1/orchestrators/" + orchestratorId.get() + "/agents", Map.of("externalId", externalId, "name", name, "role", role));
    }

    public Map<?, ?> setStatus(String agentId, String status) {
        return client.patch().uri("/api/v1/agents/" + agentId + "/status").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("status", status)).retrieve().body(Map.class);
    }

    public Map<?, ?> claim(String taskId, String agentId) {
        requireReady();
        return post("/api/v1/tasks/" + taskId + "/claim", Map.of("workspaceId", workspaceId.get(), "agentId", agentId));
    }

    public Map<?, ?> complete(String taskId) { return post("/api/v1/tasks/" + taskId + "/complete", Map.of()); }
    public Map<?, ?> start(String taskId) { return post("/api/v1/tasks/" + taskId + "/start", Map.of()); }

    public Map<?, ?> intent(String agentId, String taskId, String resourceType, String resourcePath, String intentType, int leaseSeconds) {
        requireReady();
        java.util.HashMap<String, Object> body = new java.util.HashMap<>(Map.of("workspaceId", workspaceId.get(), "agentId", agentId,
                "resourceType", resourceType, "resourcePath", resourcePath, "intentType", intentType, "leaseSeconds", leaseSeconds));
        if (taskId != null) body.put("taskId", taskId);
        return post("/api/v1/projects/" + projectId + "/resource-intents", body);
    }

    public Map<?, ?> message(String fromAgentId, String toWorkspaceId, String toAgentId, String taskId, String type, String subject, String bodyText) {
        requireReady();
        java.util.HashMap<String, Object> body = new java.util.HashMap<>(Map.of("fromWorkspaceId", workspaceId.get(),
                "toWorkspaceId", toWorkspaceId, "type", type, "body", bodyText));
        if (fromAgentId != null) body.put("fromAgentId", fromAgentId);
        if (toAgentId != null) body.put("toAgentId", toAgentId);
        if (taskId != null) body.put("taskId", taskId);
        if (subject != null) body.put("subject", subject);
        return post("/api/v1/projects/" + projectId + "/messages", body);
    }

    private void requireReady() {
        if (workspaceId.get() == null || orchestratorId.get() == null) throw new IllegalStateException("Bridge is not registered yet");
    }
}
