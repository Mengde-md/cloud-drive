package com.mengde.clouddrive.authservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtFilterFailureBoundaryTest {
    @AfterEach
    void cleanup() { SecurityContextHolder.clearContext(); }

    @Test
    void authenticatedDownstreamFailureIsNotConvertedTo401() throws Exception {
        var tokens = mock(JwtTokenService.class);
        when(tokens.verify("valid")).thenReturn(new AuthenticatedUser(9L, "carol", List.of("USER")));
        var filter = new SecurityConfiguration.JwtAuthenticationFilter(tokens,
                new SecurityConfiguration.JsonAuthFailureHandler(new ObjectMapper()));
        var request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.addHeader("Authorization", "Bearer valid");
        var response = new MockHttpServletResponse();
        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
            throw new IllegalArgumentException("downstream failure");
        })).isInstanceOf(IllegalArgumentException.class).hasMessage("downstream failure");
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEmpty();
    }

    @Test
    void decoderFailureReturns401WithoutCallingDownstream() throws Exception {
        var tokens = mock(JwtTokenService.class);
        when(tokens.verify("invalid")).thenThrow(new JwtException("invalid signature"));
        var filter = new SecurityConfiguration.JwtAuthenticationFilter(tokens,
                new SecurityConfiguration.JsonAuthFailureHandler(new ObjectMapper()));
        var request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.addHeader("Authorization", "Bearer invalid");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> { throw new AssertionError("must not forward"); });
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).doesNotContain("invalid signature");
    }
}
