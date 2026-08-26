package com.url.shortener.web;

import com.url.shortener.analytics.ClickEvent;
import com.url.shortener.analytics.ClickEventSink;
import com.url.shortener.service.LinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.util.Locale;

/**
 * The hot path. Everything here is shaped by one rule: nothing optional may sit between
 * the request and the {@code Location} header.
 *
 * <p>Two choices deserve naming:
 * <ul>
 *   <li><b>302, not 301.</b> A permanent redirect is cached by browsers and intermediaries
 *       indefinitely, which makes click counts wrong, makes expiry unenforceable, and makes
 *       a mistyped destination effectively permanent. The extra round trip buys correctness
 *       and the ability to revoke.</li>
 *   <li><b>Counting is fire-and-forget.</b> The click is handed to a bounded in-memory sink
 *       and the response is returned. Analytics can never delay or fail a redirect.</li>
 * </ul>
 */
@RestController
@Tag(name = "Redirect", description = "Public resolution endpoint")
public class RedirectController {

    private final LinkService links;
    private final ClickEventSink clicks;
    private final Clock clock;

    public RedirectController(LinkService links, ClickEventSink clicks, Clock clock) {
        this.links = links;
        this.clicks = clicks;
        this.clock = clock;
    }

    /**
     * The pattern is single-segment, so this mapping cannot swallow {@code /links/{code}},
     * {@code /actuator/**} or any other nested route.
     *
     * <p>A single-segment path such as {@code /links} does match this shape, and Spring
     * will route {@code GET /links} here rather than to the (POST-only) collection
     * endpoint. That is safe only because {@code app.code.reserved} forbids ever
     * allocating those codes, so the lookup can never resolve. The reserved list -- not
     * the route pattern -- is what makes this correct; changing one without the other
     * would let a user mint a link that shadows an API route.
     */
    @GetMapping("/{code:[A-Za-z0-9_-]{3,16}}")
    @Operation(summary = "Resolve a short code",
            description = "302 to the target, 404 for an unknown code, 410 for a retired one.")
    public ResponseEntity<Void> redirect(@PathVariable String code, HttpServletRequest request) {
        String target = links.resolve(code);

        clicks.accept(new ClickEvent(code, clock.instant(), referrerHost(request.getHeader("Referer"))));

        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, target)
                // Without this, a cached redirect makes both analytics and revocation lies.
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate")
                // Do not leak the short code onward to the destination site.
                .header("Referrer-Policy", "no-referrer")
                .build();
    }

    /**
     * Reduces a Referer header to its host. Storing only the host, never the full URL,
     * keeps query strings -- which routinely carry tokens and personal data -- out of
     * the analytics store entirely.
     */
    static String referrerHost(String referer) {
        if (referer == null || referer.isBlank()) {
            return "";
        }
        try {
            String host = new URI(referer.trim()).getHost();
            if (host == null || host.isBlank()) {
                return "";
            }
            host = host.toLowerCase(Locale.ROOT);
            return host.length() > 255 ? host.substring(0, 255) : host;
        } catch (URISyntaxException ex) {
            return "";
        }
    }
}
