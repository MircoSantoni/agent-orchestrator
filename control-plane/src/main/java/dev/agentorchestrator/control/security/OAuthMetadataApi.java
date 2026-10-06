package dev.agentorchestrator.control.security;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OAuthMetadataApi {
    private final String baseUrl;
    private final String issuer;
    private final String cognitoDomain;
    private final String humanClientId;
    private final String scope;
    private final boolean dev;

    public OAuthMetadataApi(@Value("${app.security.public-base-url:}") String baseUrl,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuer,
            @Value("${app.security.cognito-domain:}") String cognitoDomain,
            @Value("${app.security.human-client-id:}") String humanClientId,
            @Value("${app.security.required-scope:agent-orchestrator/api}") String scope,
            Environment environment) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.issuer = issuer;
        this.cognitoDomain = cognitoDomain.replaceAll("/+$", "");
        this.humanClientId = humanClientId;
        this.scope = scope;
        this.dev = environment.acceptsProfiles(Profiles.of("dev"));
    }

    @GetMapping("/.well-known/oauth-protected-resource")
    public Map<String, Object> protectedResource() {
        if (baseUrl.isBlank() || issuer.isBlank()) return Map.of("error", "OAuth is not configured");
        return Map.of("resource", baseUrl, "authorization_servers", new String[]{issuer},
                "scopes_supported", new String[]{scope}, "bearer_methods_supported", new String[]{"header"});
    }

    @GetMapping("/public/config")
    public Map<String, Object> publicConfig() {
        return Map.of("baseUrl", baseUrl, "issuer", issuer, "cognitoDomain", cognitoDomain,
                "humanClientId", humanClientId, "scope", scope, "dev", dev);
    }
}
