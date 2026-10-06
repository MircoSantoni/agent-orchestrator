package dev.agentorchestrator.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.agentorchestrator.control.security.McpAccessTokens;
import dev.agentorchestrator.control.security.McpTokenFilter;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class McpTokenFilterTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void validCredentialAuthenticatesMcpRequest() throws Exception {
        McpAccessTokens tokens = mock(McpAccessTokens.class);
        when(tokens.authenticate("ao_valid")).thenReturn(new McpAccessTokens.Identity("claude-user"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.setServletPath("/mcp");
        request.addHeader("Authorization", "Bearer ao_valid");
        AtomicBoolean reached = new AtomicBoolean();
        new McpTokenFilter(tokens).doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            reached.set(true);
            assertEquals("claude-user", ((McpAccessTokens.Identity) SecurityContextHolder.getContext()
                    .getAuthentication().getPrincipal()).sub());
        });
        assertEquals(true, reached.get());
    }

    @Test void invalidCredentialIsRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.setServletPath("/mcp");
        request.addHeader("Authorization", "Bearer ao_invalid");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();
        new McpTokenFilter(mock(McpAccessTokens.class)).doFilter(request, response,
                (req, res) -> reached.set(true));
        assertEquals(401, response.getStatus());
        assertFalse(reached.get());
    }

    @Test void credentialDoesNotAuthenticateRestRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/organizations");
        request.setServletPath("/api/v1/organizations");
        request.addHeader("Authorization", "Bearer ao_valid");
        AtomicBoolean reached = new AtomicBoolean();
        new McpTokenFilter(mock(McpAccessTokens.class)).doFilter(request, new MockHttpServletResponse(),
                (req, res) -> reached.set(true));
        assertEquals(true, reached.get());
        assertEquals(null, SecurityContextHolder.getContext().getAuthentication());
    }
}
