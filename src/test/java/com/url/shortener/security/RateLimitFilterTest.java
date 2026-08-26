package com.url.shortener.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RateLimitFilterTest {

    private static HttpServletRequest request(String forwardedFor, String remoteAddr) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn(forwardedFor);
        when(request.getRemoteAddr()).thenReturn(remoteAddr);
        return request;
    }

    @Test
    @DisplayName("takes the leftmost forwarded address, which the edge proxy controls")
    void prefersTheFirstForwardedAddress() {
        assertThat(RateLimitFilter.clientIp(request("203.0.113.7, 10.0.0.1", "10.0.0.1")))
                .isEqualTo("203.0.113.7");
    }

    @Test
    void fallsBackToTheSocketAddress() {
        assertThat(RateLimitFilter.clientIp(request(null, "203.0.113.9"))).isEqualTo("203.0.113.9");
        assertThat(RateLimitFilter.clientIp(request("   ", "203.0.113.9"))).isEqualTo("203.0.113.9");
    }

    @Test
    void neverReturnsNull() {
        assertThat(RateLimitFilter.clientIp(request(null, null))).isEqualTo("unknown");
    }
}
