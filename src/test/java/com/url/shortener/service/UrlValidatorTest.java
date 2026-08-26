package com.url.shortener.service;

import com.url.shortener.TestProperties;
import com.url.shortener.error.InvalidUrlException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * These are security tests, so the resolver is stubbed: the assertions must hold
 * regardless of what real DNS says today, and must not depend on network access.
 */
class UrlValidatorTest {

    /** Resolves any host to a fixed literal address, so rules can be probed precisely. */
    private static HostResolver resolvingTo(String literal) {
        return host -> new InetAddress[]{InetAddress.getByName(literal)};
    }

    private static final HostResolver PUBLIC = resolvingTo("93.184.216.34");

    private UrlValidator validator(HostResolver resolver) {
        return new UrlValidator(TestProperties.defaults(), resolver);
    }

    @Test
    void acceptsAPlainHttpsUrl() {
        assertThat(validator(PUBLIC).validateAndNormalize("https://example.com/path?q=1"))
                .isEqualTo("https://example.com/path?q=1");
    }

    @Test
    void trimsWhitespaceAroundTheUrl() {
        assertThat(validator(PUBLIC).validateAndNormalize("  https://example.com/x  "))
                .isEqualTo("https://example.com/x");
    }

    @ParameterizedTest
    @DisplayName("rejects non-http schemes that would turn a short link into script execution")
    @ValueSource(strings = {
            "javascript:alert(document.cookie)",
            "data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==",
            "file:///etc/passwd",
            "ftp://example.com/x",
            "mailto:someone@example.com"})
    void rejectsDisallowedSchemes(String url) {
        assertThatThrownBy(() -> validator(PUBLIC).validateAndNormalize(url))
                .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    @DisplayName("rejects userinfo, which makes a hostile host look like a trusted one")
    void rejectsCredentialsInUrl() {
        assertThatThrownBy(() ->
                validator(PUBLIC).validateAndNormalize("https://accounts.example.com@evil.test/login"))
                .isInstanceOf(InvalidUrlException.class)
                .hasMessageContaining("credentials");
    }

    @ParameterizedTest
    @DisplayName("rejects hosts that resolve inside the perimeter")
    @ValueSource(strings = {
            "127.0.0.1",        // loopback
            "10.1.2.3",         // RFC 1918
            "192.168.0.5",      // RFC 1918
            "172.16.4.4",       // RFC 1918
            "169.254.169.254",  // cloud instance metadata
            "0.0.0.0",          // any-local
            "100.100.1.1",      // carrier-grade NAT
            "198.18.0.1"})      // benchmarking range
    void rejectsPrivateAndSpecialAddresses(String address) {
        assertThatThrownBy(() ->
                validator(resolvingTo(address)).validateAndNormalize("https://looks-fine.example.com"))
                .isInstanceOf(InvalidUrlException.class)
                .hasMessageContaining("not publicly routable");
    }

    @Test
    @DisplayName("a public hostname is still rejected if any resolved address is private")
    void rejectsWhenAnyResolvedAddressIsPrivate() {
        HostResolver mixed = host -> new InetAddress[]{
                InetAddress.getByName("93.184.216.34"),
                InetAddress.getByName("10.0.0.1")};
        assertThatThrownBy(() -> validator(mixed).validateAndNormalize("https://example.com"))
                .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    @DisplayName("fails closed when the host cannot be resolved")
    void rejectsUnresolvableHost() {
        HostResolver failing = host -> {
            throw new UnknownHostException(host);
        };
        assertThatThrownBy(() -> validator(failing).validateAndNormalize("https://nope.invalid"))
                .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    @DisplayName("private targets are permitted when the guard is explicitly disabled for local dev")
    void allowsPrivateAddressesWhenGuardDisabled() {
        UrlValidator permissive =
                new UrlValidator(TestProperties.withPrivateNetworksAllowed(), resolvingTo("127.0.0.1"));
        assertThat(permissive.validateAndNormalize("http://localhost:3000/app"))
                .isEqualTo("http://localhost:3000/app");
    }

    @Test
    @DisplayName("refuses to shorten a link back to this service")
    void rejectsSelfReferentialLinks() {
        assertThatThrownBy(() -> validator(PUBLIC).validateAndNormalize("https://sho.rt/abc123"))
                .isInstanceOf(InvalidUrlException.class)
                .hasMessageContaining("this service");
    }

    @Test
    void rejectsRelativeUrls() {
        assertThatThrownBy(() -> validator(PUBLIC).validateAndNormalize("/just/a/path"))
                .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void rejectsBlankUrls() {
        assertThatThrownBy(() -> validator(PUBLIC).validateAndNormalize("   "))
                .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void rejectsOverlongUrls() {
        String longUrl = "https://example.com/" + "x".repeat(2100);
        assertThatThrownBy(() -> validator(PUBLIC).validateAndNormalize(longUrl))
                .isInstanceOf(InvalidUrlException.class)
                .hasMessageContaining("maximum length");
    }
}
