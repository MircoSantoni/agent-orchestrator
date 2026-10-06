package dev.agentorchestrator.control.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class McpTokenFilter extends OncePerRequestFilter {
    private final McpAccessTokens tokens;
    public McpTokenFilter(McpAccessTokens tokens) { this.tokens = tokens; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"/mcp".equals(request.getServletPath());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ao_", 0, 10)) {
            chain.doFilter(request, response);
            return;
        }
        McpAccessTokens.Identity identity = tokens.authenticate(header.substring(7));
        if (identity == null) {
            response.sendError(401, "Invalid MCP access token");
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(identity, null, java.util.List.of()));
        chain.doFilter(request, response);
    }
}
