package com.url.shortener.service;

import com.url.shortener.config.AppProperties;
import com.url.shortener.error.InvalidUrlException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Gatekeeper for target URLs.
 *
 * <p>A URL shortener is an attractive intermediary: it turns an attacker-controlled
 * destination into a link that wears the host organisation's domain. The rules here
 * exist to stop three distinct abuses:
 * <ol>
 *   <li><b>Internal reconnaissance.</b> A short link pointing at {@code 169.254.169.254}
 *       or an RFC 1918 address turns any employee's browser into a probe of the internal
 *       network. Hosts are resolved and every returned address is checked.</li>
 *   <li><b>Scheme abuse.</b> {@code javascript:}, {@code data:} and {@code file:} targets
 *       are rejected outright; only http and https can be shortened.</li>
 *   <li><b>Credential-looking URLs.</b> {@code https://accounts.example.com@evil.test}
 *       reads as a trusted host to a human. Userinfo is rejected.</li>
 * </ol>
 *
 * <p><b>Known limitation:</b> resolution happens at creation time, so a DNS-rebinding
 * attacker can point a validated hostname at a private address afterwards. Closing that
 * requires validation at redirect time or an egress proxy; recorded in
 * docs/testing-and-tradeoffs.md rather than silently ignored.
 */
@Component
public class UrlValidator {

    private static final Logger log = LoggerFactory.getLogger(UrlValidator.class);

    private final AppProperties properties;
    private final HostResolver hostResolver;
    private final String ownHost;

    public UrlValidator(AppProperties properties, HostResolver hostResolver) {
        this.properties = properties;
        this.hostResolver = hostResolver;
        this.ownHost = extractOwnHost(properties.baseUrl());
    }

    /**
     * @return the normalised, ASCII-encoded URL to persist
     * @throws InvalidUrlException if the URL may not be shortened
     */
    public String validateAndNormalize(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new InvalidUrlException("URL must not be blank");
        }
        String candidate = rawUrl.trim();
        if (candidate.length() > properties.security().maxUrlLength()) {
            throw new InvalidUrlException(
                    "URL exceeds the maximum length of " + properties.security().maxUrlLength());
        }

        URI uri = parse(candidate);

        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new InvalidUrlException("URL must be absolute and include a scheme");
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new InvalidUrlException("Only http and https URLs can be shortened");
        }

        if (uri.getRawUserInfo() != null) {
            throw new InvalidUrlException("URLs containing credentials are not accepted");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            // Also catches malformed authorities that URI parses but cannot interpret.
            throw new InvalidUrlException("URL must contain a valid host");
        }
        host = host.toLowerCase(Locale.ROOT);

        if (ownHost != null && host.equals(ownHost)) {
            throw new InvalidUrlException("Refusing to shorten a link to this service");
        }

        if (properties.security().blockPrivateNetworks()) {
            assertPubliclyRoutable(host);
        }

        return uri.toASCIIString();
    }

    private URI parse(String candidate) {
        try {
            URI uri = new URI(candidate);
            if (!uri.isAbsolute()) {
                throw new InvalidUrlException("URL must be absolute and include a scheme");
            }
            return uri;
        } catch (URISyntaxException ex) {
            throw new InvalidUrlException("URL is not well formed");
        }
    }

    /**
     * Fails closed: an unresolvable host is rejected rather than accepted on trust.
     * A link nobody can resolve has no value anyway, so the strict choice costs nothing.
     */
    private void assertPubliclyRoutable(String host) {
        InetAddress[] addresses;
        try {
            addresses = hostResolver.resolve(host);
        } catch (UnknownHostException ex) {
            log.debug("Rejecting unresolvable host {}", host);
            throw new InvalidUrlException("The host '" + host + "' could not be resolved");
        }
        if (addresses == null || addresses.length == 0) {
            throw new InvalidUrlException("The host '" + host + "' could not be resolved");
        }
        for (InetAddress address : addresses) {
            if (isRestricted(address)) {
                // The message stays vague on purpose: confirming which internal ranges
                // exist would itself be a disclosure.
                throw new InvalidUrlException("The target host is not publicly routable");
            }
        }
    }

    static boolean isRestricted(InetAddress address) {
        return address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isAnyLocalAddress()
                || address.isMulticastAddress()
                || isCarrierGradeNatOrSpecial(address);
    }

    /**
     * {@code isSiteLocalAddress} misses ranges that are still not safe to reach:
     * 100.64.0.0/10 (CGNAT), 192.0.0.0/24, and 198.18.0.0/15 (benchmarking).
     */
    private static boolean isCarrierGradeNatOrSpecial(InetAddress address) {
        byte[] octets = address.getAddress();
        if (octets.length != 4) {
            return false;
        }
        int first = octets[0] & 0xFF;
        int second = octets[1] & 0xFF;
        if (first == 100 && second >= 64 && second <= 127) {
            return true;
        }
        if (first == 192 && second == 0 && (octets[2] & 0xFF) == 0) {
            return true;
        }
        return first == 198 && (second == 18 || second == 19);
    }

    private static String extractOwnHost(String baseUrl) {
        try {
            String host = new URI(baseUrl).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (URISyntaxException ex) {
            log.warn("app.base-url '{}' is not a valid URI; self-link protection disabled", baseUrl);
            return null;
        }
    }
}
