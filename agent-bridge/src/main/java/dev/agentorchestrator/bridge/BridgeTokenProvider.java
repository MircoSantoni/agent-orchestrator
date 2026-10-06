package dev.agentorchestrator.bridge;

import com.sun.net.httpserver.HttpServer;
import java.awt.Desktop;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/** Public-client authorization code + PKCE. Tokens stay in memory and refresh before expiry. */
@Component
public class BridgeTokenProvider {
    private static final Logger log = LoggerFactory.getLogger(BridgeTokenProvider.class);
    private final String staticToken;
    private final String authorizationUrl;
    private final String tokenUrl;
    private final String clientId;
    private final String redirectUri;
    private final String scope;
    private final String resource;
    private final RestClient oauth = RestClient.create();
    private String accessToken;
    private String refreshToken;
    private Instant expiresAt = Instant.EPOCH;

    public BridgeTokenProvider(@Value("${bridge.token:}") String staticToken,
            @Value("${bridge.oidc.authorization-url:}") String authorizationUrl,
            @Value("${bridge.oidc.token-url:}") String tokenUrl,
            @Value("${bridge.oidc.client-id:}") String clientId,
            @Value("${bridge.oidc.redirect-uri:http://127.0.0.1:8765/callback}") String redirectUri,
            @Value("${bridge.oidc.scope:openid profile agent-orchestrator/api}") String scope,
            @Value("${bridge.oidc.resource:}") String resource) {
        this.staticToken = staticToken;
        this.authorizationUrl = authorizationUrl;
        this.tokenUrl = tokenUrl;
        this.clientId = clientId;
        this.redirectUri = redirectUri;
        this.scope = scope;
        this.resource = resource;
    }

    public synchronized String token() {
        if (!staticToken.isBlank()) return staticToken;
        if (authorizationUrl.isBlank() || tokenUrl.isBlank() || clientId.isBlank() || resource.isBlank()) return "";
        if (accessToken != null && Instant.now().isBefore(expiresAt.minusSeconds(60))) return accessToken;
        if (refreshToken != null) {
            try { exchange(Map.of("grant_type", "refresh_token", "client_id", clientId, "refresh_token", refreshToken));
                return accessToken;
            } catch (Exception e) { log.warn("Token refresh failed; interactive sign-in required: {}", e.getMessage()); }
        }
        signIn();
        return accessToken;
    }

    private void signIn() {
        URI redirect = URI.create(redirectUri);
        if (!"http".equals(redirect.getScheme()) || !("127.0.0.1".equals(redirect.getHost()) || "localhost".equals(redirect.getHost())))
            throw new IllegalStateException("Bridge redirect URI must use a loopback HTTP address");
        String verifier = randomUrlSafe(64);
        String state = randomUrlSafe(32);
        CompletableFuture<String> code = new CompletableFuture<>();
        HttpServer callback;
        try {
            callback = HttpServer.create(new InetSocketAddress("127.0.0.1", redirect.getPort()), 0);
        } catch (Exception e) { throw new IllegalStateException("Cannot open Bridge login callback", e); }
        callback.createContext(redirect.getPath(), exchange -> {
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            String message;
            if (!state.equals(query.get("state"))) {
                code.completeExceptionally(new IllegalStateException("OAuth state mismatch")); message = "Login rejected: state mismatch";
            } else if (query.containsKey("error")) {
                code.completeExceptionally(new IllegalStateException("OAuth login failed: " + query.get("error")));
                message = "Login failed";
            } else if (!query.containsKey("code")) {
                code.completeExceptionally(new IllegalStateException("Authorization code missing")); message = "Login failed";
            } else {
                code.complete(query.get("code")); message = "Login complete. You may close this tab.";
            }
            byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        callback.start();
        try {
            String url = authorizationUrl + "?" + form(Map.of("response_type", "code", "client_id", clientId,
                    "redirect_uri", redirectUri, "scope", scope, "resource", resource, "state", state,
                    "code_challenge_method", "S256", "code_challenge", challenge(verifier)));
            log.info("Open this URL to connect the Agent Bridge: {}", url);
            try { if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI.create(url)); }
            catch (Exception e) { log.info("Open the login URL manually in your browser"); }
            String authorizationCode = code.get(300, TimeUnit.SECONDS);
            exchange(Map.of("grant_type", "authorization_code", "client_id", clientId,
                    "code", authorizationCode, "redirect_uri", redirectUri, "code_verifier", verifier));
            log.info("Agent Bridge authentication completed");
        } catch (Exception e) {
            throw new IllegalStateException("Bridge sign-in did not complete", e);
        } finally {
            callback.stop(0);
        }
    }

    private void exchange(Map<String, String> values) {
        LinkedMultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        values.forEach(body::add);
        Map<?, ?> tokens = oauth.post().uri(tokenUrl).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body).retrieve().body(Map.class);
        if (tokens == null || !(tokens.get("access_token") instanceof String token))
            throw new IllegalStateException("OAuth token response did not contain an access token");
        accessToken = token;
        if (tokens.get("refresh_token") instanceof String refresh) refreshToken = refresh;
        int expiresIn = tokens.get("expires_in") instanceof Number n ? n.intValue() : 300;
        expiresAt = Instant.now().plusSeconds(expiresIn);
    }

    static String challenge(String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }

    private static String randomUrlSafe(int bytes) {
        byte[] value = new byte[bytes]; new SecureRandom().nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
    private static String form(Map<String, String> values) {
        return values.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" +
                URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).reduce((a, b) -> a + "&" + b).orElse("");
    }
    private static Map<String, String> parseQuery(String query) {
        Map<String, String> values = new HashMap<>();
        if (query == null) return values;
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            values.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
        }
        return values;
    }
}
