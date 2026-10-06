package dev.agentorchestrator.control.web;

import dev.agentorchestrator.control.data.CoordinationService;
import dev.agentorchestrator.control.data.Store;
import dev.agentorchestrator.control.security.Actor;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** A narrow, stateless MCP 2026-07-28 adapter over the existing authorization and domain services. */
@RestController
public class McpApi {
    private static final String VERSION = "2026-07-28";
    private static final String LEGACY_VERSION = "2025-11-25";
    private static final String VERSION_KEY = "io.modelcontextprotocol/protocolVersion";
    private static final String CAPABILITIES_KEY = "io.modelcontextprotocol/clientCapabilities";
    private static final Map<String, Object> SERVER_INFO = Map.of("name", "agent-orchestrator", "version", "0.1.0");
    private final Store store;
    private final CoordinationService coordination;
    private final Actor actor;
    private final List<String> allowedOrigins;
    private final List<Map<String, Object>> tools;

    public McpApi(Store store, CoordinationService coordination, Actor actor,
                  @Value("${app.mcp.allowed-origins:}") String allowedOrigins) {
        this.store = store;
        this.coordination = coordination;
        this.actor = actor;
        this.allowedOrigins = allowedOrigins.isBlank() ? List.of() :
                List.of(allowedOrigins.split(",")).stream().map(String::trim).toList();
        this.tools = List.of(
                tool("list_projects", "List projects available to this account", fields()),
                tool("get_project", "Read project details", fields("projectId", "string"), "projectId"),
                tool("list_workspaces", "List project workspaces and their owners", fields("projectId", "string"), "projectId"),
                tool("list_tasks", "List project tasks and their owners", fields("projectId", "string"), "projectId"),
                tool("list_agents", "List agents working in a project", fields("projectId", "string"), "projectId"),
                tool("list_context", "List project facts, proposals and approved decisions", fields("projectId", "string"), "projectId"),
                tool("list_resource_intents", "List resource intents and conflicts in a project", fields("projectId", "string"), "projectId"),
                tool("list_inbox", "List messages for a workspace you own", fields("workspaceId", "string"), "workspaceId"),
                tool("connect_agent", "Register an agent directly through MCP; repeat with the same keys to reconnect", fields("projectId", "string", "workspaceName", "string", "displayName", "string", "agentKey", "string", "agentName", "string", "role", "string", "model", "string"), "projectId", "workspaceName", "displayName", "agentKey", "agentName"),
                tool("heartbeat_agent", "Refresh presence for an agent and its remote workspace", fields("agentId", "string"), "agentId"),
                tool("claim_task", "Atomically claim a ready task for your agent", fields("taskId", "string", "workspaceId", "string", "agentId", "string"), "taskId", "workspaceId", "agentId"),
                tool("start_task", "Start a task claimed by your agent", fields("taskId", "string"), "taskId"),
                tool("complete_task", "Complete a task owned by your workspace", fields("taskId", "string"), "taskId"),
                tool("announce_resource_intent", "Declare a read or write intent before touching a resource", fields("projectId", "string", "workspaceId", "string", "agentId", "string", "taskId", "string", "resourceType", "string", "resourcePath", "string", "intentType", "string", "leaseSeconds", "integer"), "projectId", "workspaceId", "agentId", "resourceType", "resourcePath", "intentType", "leaseSeconds"),
                tool("propose_context", "Create a proposal pending human approval", fields("projectId", "string", "workspaceId", "string", "agentId", "string", "taskId", "string", "title", "string", "content", "string"), "projectId", "title", "content"),
                tool("send_message", "Send a coordination message or task handoff", fields("projectId", "string", "fromWorkspaceId", "string", "fromAgentId", "string", "toWorkspaceId", "string", "toAgentId", "string", "taskId", "string", "type", "string", "subject", "string", "body", "string"), "projectId", "fromWorkspaceId", "toWorkspaceId", "type", "body"),
                tool("read_message", "Mark a message in your inbox as read", fields("messageId", "string"), "messageId"),
                tool("ack_message", "Acknowledge a message in your inbox", fields("messageId", "string"), "messageId"),
                tool("accept_handoff", "Accept a handoff message and create its child task", fields("messageId", "string"), "messageId"));
    }

    @PostMapping(value = "/mcp", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> mcp(@RequestBody Map<String, Object> request,
            @RequestHeader(value = "MCP-Protocol-Version", required = false) String versionHeader,
            @RequestHeader(value = "Mcp-Method", required = false) String methodHeader,
            @RequestHeader(value = "Mcp-Name", required = false) String nameHeader,
            @RequestHeader(value = "Origin", required = false) String origin) {
        Object id = request.get("id");
        if (origin != null && !allowedOrigins.contains(origin)) return error(HttpStatus.FORBIDDEN, id, -32000, "Origin not allowed");
        if (!"2.0".equals(request.get("jsonrpc")) || !(request.get("method") instanceof String method))
            return error(HttpStatus.BAD_REQUEST, id, -32600, "Invalid JSON-RPC request");
        if ("notifications/initialized".equals(method) && id == null)
            return ResponseEntity.status(HttpStatus.ACCEPTED).build();
        if (id == null) return error(HttpStatus.BAD_REQUEST, null, -32600, "Request id required");
        if (legacy(request, versionHeader, method)) return legacyCall(id, method, request.get("params"));
        if (!(request.get("params") instanceof Map<?, ?> params) || !(params.get("_meta") instanceof Map<?, ?> meta))
            return error(HttpStatus.BAD_REQUEST, id, -32602, "Request metadata required");
        if (versionHeader == null || methodHeader == null)
            return error(HttpStatus.BAD_REQUEST, id, -32020, "Required MCP header missing");
        if (!VERSION.equals(versionHeader))
            return error(HttpStatus.BAD_REQUEST, id, -32022, "Unsupported protocol version", Map.of("supported", List.of(VERSION)));
        if (!versionHeader.equals(meta.get(VERSION_KEY)) || !method.equals(methodHeader))
            return error(HttpStatus.BAD_REQUEST, id, -32020, "MCP headers do not match request");
        if (!(meta.get(CAPABILITIES_KEY) instanceof Map<?, ?>))
            return error(HttpStatus.BAD_REQUEST, id, -32602, "Client capabilities required");
        if ("tools/call".equals(method)) {
            if (!(params.get("name") instanceof String name) || !name.equals(decoded(nameHeader)))
                return error(HttpStatus.BAD_REQUEST, id, -32020, "Mcp-Name does not match request");
        }
        // Authentication is enforced by Spring Security; Actor validates the signed JWT's client and token_use.
        actor.sub();
        return switch (method) {
            case "server/discover" -> ok(id, Map.of("resultType", "complete", "supportedVersions", List.of(VERSION),
                    "capabilities", Map.of("tools", Map.of("listChanged", false)), "ttlMs", 0, "cacheScope", "private"));
            case "tools/list" -> ok(id, Map.of("resultType", "complete", "tools", tools, "ttlMs", 0, "cacheScope", "private"));
            case "tools/call" -> call(id, params, false);
            default -> error(HttpStatus.NOT_FOUND, id, -32601, "Method not found");
        };
    }

    private boolean legacy(Map<String, Object> request, String versionHeader, String method) {
        if ("initialize".equals(method)) return true;
        if (!List.of("tools/list", "tools/call").contains(method)) return false;
        if (LEGACY_VERSION.equals(versionHeader)) return true;
        if (versionHeader != null) return false;
        return request.get("params") instanceof Map<?, ?> params && !params.containsKey("_meta");
    }

    private ResponseEntity<Map<String, Object>> legacyCall(Object id, String method, Object rawParams) {
        actor.sub();
        if (!(rawParams instanceof Map<?, ?> params)) return error(HttpStatus.BAD_REQUEST, id, -32602, "Params object required");
        return switch (method) {
            case "initialize" -> legacyOk(id, Map.of("protocolVersion", LEGACY_VERSION,
                    "capabilities", Map.of("tools", Map.of("listChanged", false)), "serverInfo", SERVER_INFO));
            case "tools/list" -> legacyOk(id, Map.of("tools", tools));
            case "tools/call" -> call(id, params, true);
            default -> error(HttpStatus.NOT_FOUND, id, -32601, "Method not found");
        };
    }

    private ResponseEntity<Map<String, Object>> call(Object id, Map<?, ?> params, boolean legacy) {
        if (!(params.get("name") instanceof String name))
            return error(HttpStatus.BAD_REQUEST, id, -32602, "Tool name required");
        if (tools.stream().noneMatch(t -> name.equals(t.get("name"))))
            return error(HttpStatus.NOT_FOUND, id, -32602, "Unknown tool");
        try {
            if (!(params.get("arguments") instanceof Map<?, ?> arguments)) throw new IllegalArgumentException("Arguments object required");
            String output = execute(name, arguments);
            Map<String, Object> result = Map.of("content", List.of(Map.of("type", "text", "text", output)), "isError", false);
            return legacy ? legacyOk(id, result) : ok(id, withResultType(result));
        } catch (ApiProblem | IllegalArgumentException e) {
            Map<String, Object> result = Map.of("content", List.of(Map.of("type", "text", "text", e.getMessage())), "isError", true);
            return legacy ? legacyOk(id, result) : ok(id, withResultType(result));
        }
    }

    private static Map<String, Object> withResultType(Map<String, Object> result) {
        Map<String, Object> value = new LinkedHashMap<>(result);
        value.put("resultType", "complete");
        return value;
    }

    private String execute(String name, Map<?, ?> a) {
        return switch (name) {
            case "list_projects" -> store.projects();
            case "get_project" -> store.project(uuid(a, "projectId"));
            case "list_workspaces" -> store.workspaces(uuid(a, "projectId"));
            case "list_tasks" -> store.tasks(uuid(a, "projectId"));
            case "list_agents" -> store.agents(uuid(a, "projectId"));
            case "list_context" -> coordination.context(uuid(a, "projectId"));
            case "list_resource_intents" -> coordination.intents(uuid(a, "projectId"));
            case "list_inbox" -> coordination.inbox(uuid(a, "workspaceId"));
            case "connect_agent" -> {
                UUID workspaceId = store.registerWorkspace(uuid(a, "projectId"), string(a, "workspaceName"),
                        "remote-mcp", "Remote MCP", string(a, "displayName"));
                UUID orchestratorId = store.registerOrchestrator(workspaceId, "mcp", "REMOTE_MCP", optionalString(a, "model"));
                UUID agentId = store.registerAgent(orchestratorId, string(a, "agentKey"), string(a, "agentName"),
                        optionalString(a, "role"), optionalString(a, "model"));
                yield "{\"workspaceId\":\"" + workspaceId + "\",\"orchestratorId\":\"" + orchestratorId +
                        "\",\"agentId\":\"" + agentId + "\"}";
            }
            case "heartbeat_agent" -> {
                store.heartbeatAgentTree(uuid(a, "agentId"));
                yield "{\"status\":\"ONLINE\"}";
            }
            case "claim_task" -> {
                store.claim(uuid(a, "taskId"), uuid(a, "workspaceId"), uuid(a, "agentId"));
                yield "{\"status\":\"CLAIMED\"}";
            }
            case "start_task" -> {
                store.transitionTask(uuid(a, "taskId"), "CLAIMED", "IN_PROGRESS");
                yield "{\"status\":\"IN_PROGRESS\"}";
            }
            case "complete_task" -> {
                store.transitionTask(uuid(a, "taskId"), "IN_PROGRESS", "COMPLETED");
                yield "{\"status\":\"COMPLETED\"}";
            }
            case "announce_resource_intent" -> "{\"id\":\"" + coordination.createIntent(uuid(a, "projectId"), uuid(a, "workspaceId"),
                    uuid(a, "agentId"), optionalUuid(a, "taskId"), string(a, "resourceType"), string(a, "resourcePath"),
                    string(a, "intentType"), integer(a, "leaseSeconds")) + "\"}";
            case "propose_context" -> coordination.contextEntry(coordination.createContext(uuid(a, "projectId"),
                    optionalUuid(a, "workspaceId"), optionalUuid(a, "agentId"), optionalUuid(a, "taskId"),
                    "PROPOSAL", string(a, "title"), string(a, "content")));
            case "send_message" -> coordination.message(coordination.sendMessage(uuid(a, "projectId"),
                    uuid(a, "fromWorkspaceId"), optionalUuid(a, "fromAgentId"), uuid(a, "toWorkspaceId"),
                    optionalUuid(a, "toAgentId"), optionalUuid(a, "taskId"), string(a, "type"),
                    optionalString(a, "subject"), string(a, "body")));
            case "read_message" -> {
                coordination.read(uuid(a, "messageId"));
                yield "{\"status\":\"READ\"}";
            }
            case "ack_message" -> {
                coordination.acknowledge(uuid(a, "messageId"));
                yield "{\"status\":\"ACKNOWLEDGED\"}";
            }
            case "accept_handoff" -> "{\"taskId\":\"" + coordination.acceptHandoff(uuid(a, "messageId")) + "\"}";
            default -> throw new IllegalArgumentException("Unknown tool");
        };
    }

    private static String string(Map<?, ?> a, String key) {
        Object value = a.get(key);
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException(key + " is required");
        return text;
    }
    private static String optionalString(Map<?, ?> a, String key) {
        Object value = a.get(key);
        if (value == null) return null;
        if (!(value instanceof String text)) throw new IllegalArgumentException(key + " must be a string");
        return text;
    }
    private static UUID uuid(Map<?, ?> a, String key) {
        try { return UUID.fromString(string(a, key)); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException(key + " must be a UUID"); }
    }
    private static UUID optionalUuid(Map<?, ?> a, String key) {
        return a.get(key) == null ? null : uuid(a, key);
    }
    private static int integer(Map<?, ?> a, String key) {
        Object value = a.get(key);
        if (!(value instanceof Number number) || number.doubleValue() != Math.rint(number.doubleValue())
                || number.longValue() < Integer.MIN_VALUE || number.longValue() > Integer.MAX_VALUE)
            throw new IllegalArgumentException(key + " must be an integer");
        return number.intValue();
    }
    private static Map<String, Object> fields(String... pairs) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) properties.put(pairs[i], Map.of("type", pairs[i + 1]));
        return properties;
    }
    private static Map<String, Object> tool(String name, String description, Map<String, Object> properties, String... required) {
        return Map.of("name", name, "description", description,
                "inputSchema", Map.of("type", "object", "properties", properties, "required", List.of(required), "additionalProperties", false));
    }
    private static String decoded(String header) {
        if (header == null) return null;
        if (header.startsWith("=?base64?") && header.endsWith("?=")) {
            try { return new String(Base64.getDecoder().decode(header.substring(9, header.length() - 2)), java.nio.charset.StandardCharsets.UTF_8); }
            catch (IllegalArgumentException e) { return null; }
        }
        return header;
    }
    private static ResponseEntity<Map<String, Object>> ok(Object id, Map<String, Object> result) {
        Map<String, Object> value = new LinkedHashMap<>(result);
        value.put("_meta", Map.of("io.modelcontextprotocol/serverInfo", SERVER_INFO));
        return ResponseEntity.ok(Map.of("jsonrpc", "2.0", "id", id, "result", value));
    }
    private static ResponseEntity<Map<String, Object>> legacyOk(Object id, Map<String, Object> result) {
        return ResponseEntity.ok(Map.of("jsonrpc", "2.0", "id", id, "result", result));
    }
    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, Object id, int code, String message) {
        return error(status, id, code, message, Map.of());
    }
    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, Object id, int code, String message, Map<String, Object> data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0"); body.put("id", id);
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("code", code); problem.put("message", message);
        if (!data.isEmpty()) problem.put("data", data);
        body.put("error", problem);
        return ResponseEntity.status(status).body(body);
    }
}
