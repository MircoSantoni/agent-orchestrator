package dev.agentorchestrator.control.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.beans.factory.annotation.Value;
import java.util.Arrays;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfiguration {
    @Bean
    FilterRegistrationBean<McpTokenFilter> disableServletRegistration(McpTokenFilter filter) {
        FilterRegistrationBean<McpTokenFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    @Profile("!dev")
    SecurityFilterChain productionSecurity(HttpSecurity http,
            McpTokenFilter mcpTokenFilter,
            @Value("${app.security.public-base-url:}") String publicBaseUrl,
            @Value("${app.security.required-scope:agent-orchestrator/api}") String requiredScope,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuer) throws Exception {
        return http.csrf(csrf -> csrf.disable()).cors(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/", "/index.html", "/app.js", "/styles.css", "/fonts/**", "/public/config",
                                "/downloads/workspace-mcp.tgz", "/downloads/workspace-mcp.md",
                                "/.well-known/oauth-protected-resource").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, error) -> {
                    if (request.getRequestURI().equals("/mcp") && !publicBaseUrl.isBlank()) {
                        response.setHeader("WWW-Authenticate", "Bearer resource_metadata=\"" + publicBaseUrl +
                                "/.well-known/oauth-protected-resource\", scope=\"" + requiredScope + "\"");
                    }
                    response.sendError(401);
                }))
                .oauth2ResourceServer(oauth -> oauth.bearerTokenResolver(request -> {
                            String token = new DefaultBearerTokenResolver().resolve(request);
                            return "/mcp".equals(request.getServletPath()) && token != null && token.startsWith("ao_")
                                    ? null : token;
                        }).jwt(Customizer.withDefaults())
                        .protectedResourceMetadata(metadata -> metadata.protectedResourceMetadataCustomizer(builder ->
                                builder.resource(publicBaseUrl).authorizationServer(issuer).scope(requiredScope)
                                        .tlsClientCertificateBoundAccessTokens(false))))
                .addFilterBefore(mcpTokenFilter, BasicAuthenticationFilter.class)
                .build();
    }

    @Bean
    @Profile("!dev")
    UrlBasedCorsConfigurationSource corsConfigurationSource(@Value("${app.mcp.allowed-origins:}") String allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins.isBlank() ? java.util.List.of() :
                Arrays.stream(allowedOrigins.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList());
        config.setAllowedMethods(java.util.List.of("POST", "OPTIONS"));
        config.setAllowedHeaders(java.util.List.of("Authorization", "Content-Type", "MCP-Protocol-Version", "Mcp-Method", "Mcp-Name", "Accept"));
        config.setExposedHeaders(java.util.List.of("WWW-Authenticate", "MCP-Protocol-Version"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/mcp", config);
        return source;
    }

    @Bean
    @Profile("dev")
    SecurityFilterChain developmentSecurity(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }
}
