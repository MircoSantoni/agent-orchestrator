package dev.agentorchestrator.control.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.beans.factory.annotation.Value;

@Configuration
public class SecurityConfiguration {
    @Bean
    @Profile("!dev")
    SecurityFilterChain productionSecurity(HttpSecurity http,
            @Value("${app.security.public-base-url:}") String publicBaseUrl,
            @Value("${app.security.required-scope:agent-orchestrator/api}") String requiredScope,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuer) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/", "/index.html", "/app.js", "/public/config",
                                "/.well-known/oauth-protected-resource").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, error) -> {
                    if (request.getRequestURI().equals("/mcp") && !publicBaseUrl.isBlank()) {
                        response.setHeader("WWW-Authenticate", "Bearer resource_metadata=\"" + publicBaseUrl +
                                "/.well-known/oauth-protected-resource\", scope=\"" + requiredScope + "\"");
                    }
                    response.sendError(401);
                }))
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults())
                        .protectedResourceMetadata(metadata -> metadata.protectedResourceMetadataCustomizer(builder ->
                                builder.resource(publicBaseUrl).authorizationServer(issuer).scope(requiredScope)
                                        .tlsClientCertificateBoundAccessTokens(false))))
                .build();
    }

    @Bean
    @Profile("dev")
    SecurityFilterChain developmentSecurity(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }
}
