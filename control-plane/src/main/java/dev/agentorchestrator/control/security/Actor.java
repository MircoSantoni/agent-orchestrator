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
    private final boolean dev;

    public Actor(HttpServletRequest request,
                 @Value("${app.security.human-client-id:}") String humanClientId,
                 @Value("${app.security.bridge-client-id:}") String bridgeClientId,
                 Environment environment) {
        this.request = request;
        this.humanClientId = humanClientId;
        this.bridgeClientId = bridgeClientId;
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
        if (clientId == null || (!clientId.equals(humanClientId) && !clientId.equals(bridgeClientId)))
            throw ApiProblem.forbidden("Token was issued to another client");
        return jwt.getSubject();
    }

    public void requireHuman() {
        if (dev) return;
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
