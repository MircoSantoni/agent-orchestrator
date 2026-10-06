package dev.agentorchestrator.control.security;

import dev.agentorchestrator.control.web.ApiProblem;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class McpAccessTokens {
    public record Identity(String sub) {}
    public record TokenInfo(UUID id, String name, Instant createdAt, Instant expiresAt,
                            Instant lastUsedAt, Instant revokedAt) {}

    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();

    public McpAccessTokens(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Map<String, Object> create(String ownerSub, String name) {
        String label = name == null ? "" : name.trim();
        if (label.isEmpty() || label.length() > 80) throw ApiProblem.badRequest("Name must contain 1 to 80 characters");
        UUID id = UUID.randomUUID();
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String token = "ao_" + id + "_" + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        Instant expiresAt = Instant.now().plus(90, ChronoUnit.DAYS);
        jdbc.update("INSERT INTO mcp_access_token (id, owner_sub, name, secret_hash, expires_at) VALUES (?, ?, ?, ?, ?)",
                id, ownerSub, label, hash(token), java.sql.Timestamp.from(expiresAt));
        return Map.of("id", id.toString(), "name", label, "token", token, "expiresAt", expiresAt.toString());
    }

    public List<TokenInfo> list(String ownerSub) {
        return jdbc.query("SELECT id, name, created_at, expires_at, last_used_at, revoked_at " +
                "FROM mcp_access_token WHERE owner_sub = ? ORDER BY created_at DESC", (rs, i) ->
                new TokenInfo(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("last_used_at") == null ? null : rs.getTimestamp("last_used_at").toInstant(),
                        rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant()), ownerSub);
    }

    public void revoke(String ownerSub, UUID id) {
        int changed = jdbc.update("UPDATE mcp_access_token SET revoked_at = now() WHERE id = ? AND owner_sub = ? AND revoked_at IS NULL", id, ownerSub);
        if (changed == 0) throw ApiProblem.notFound("Token not found");
    }

    public Identity authenticate(String token) {
        if (token == null || !token.matches("ao_[0-9a-f-]{36}_[A-Za-z0-9_-]{43}")) return null;
        UUID id;
        try { id = UUID.fromString(token.substring(3, 39)); }
        catch (IllegalArgumentException e) { return null; }
        List<Map.Entry<String, byte[]>> rows = jdbc.query("SELECT owner_sub, secret_hash FROM mcp_access_token " +
                "WHERE id = ? AND revoked_at IS NULL AND expires_at > now()", (rs, i) ->
                Map.entry(rs.getString("owner_sub"), rs.getBytes("secret_hash")), id);
        if (rows.size() != 1 || !MessageDigest.isEqual(rows.getFirst().getValue(), hash(token))) return null;
        jdbc.update("UPDATE mcp_access_token SET last_used_at = now() WHERE id = ?", id);
        return new Identity(rows.getFirst().getKey());
    }

    private static byte[] hash(String token) {
        try { return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
