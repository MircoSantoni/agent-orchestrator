package dev.agentorchestrator.control.security;

import dev.agentorchestrator.control.web.ApiProblem;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class Actor {
    private final HttpServletRequest request;
    private final String humanClientId;
    private final String bridgeClientId;
    private final String mcpClientId;
    private final String requiredScope;
    private final String audience;
    private final boolean dev;

    public Actor(HttpServletRequest request,
                 @Value("${app.security.human-client-id:}") String humanClientId,
                 @Value("${app.security.bridge-client-id:}") String bridgeClientId,
                 @Value("${app.security.mcp-client-id:}") String mcpClientId,
                 @Value("${app.security.required-scope:agent-orchestrator/api}") String requiredScope,
                 @Value("${app.security.audience:}") String audience,
                 Environment environment) {
        this.request = request;
        this.humanClientId = humanClientId;
        this.bridgeClientId = bridgeClientId;
        this.mcpClientId = mcpClientId;
        this.requiredScope = requiredScope;
        this.audience = audience;
        this.dev = environment.acceptsProfiles(Profiles.of("dev"));
    }

    public String sub() {
        if (dev) {
            String value = request.getHeader("X-Dev-User");
            return value == null || value.isBlank() ? "local-user" : value;
        }
        Jwt jwt = jwt();
        if (!"access".equals(jwt.getClaimAsString("token_use"))) throw ApiProblem.forbidden("Access token required");
        String clientId = jwt.getClaimAsString("client_id");
        if (clientId == null || clientId.isBlank() || (!clientId.equals(humanClientId) && !clientId.equals(bridgeClientId)
                && !clientId.equals(mcpClientId)))
            throw ApiProblem.forbidden("Token was issued to another client");
        if (audience.isBlank() || !jwt.getAudience().contains(audience))
            throw ApiProblem.forbidden("Token audience does not match this API");
        String scopes = jwt.getClaimAsString("scope");
        if (requiredScope.isBlank() || scopes == null || !java.util.Arrays.asList(scopes.split(" ")).contains(requiredScope))
            throw ApiProblem.forbidden("Required API scope missing");
        return jwt.getSubject();
    }

    public void requireHuman() {
        if (dev) return;
        sub();
        if (humanClientId.isBlank() || !humanClientId.equals(jwt().getClaimAsString("client_id"))) {
            throw ApiProblem.forbidden("Human session required");
        }
    }

    private Jwt jwt() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) throw ApiProblem.unauthorized("Authentication required");
        return jwt;
    }
}
