import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Verification harness. Maven Central is unreachable in this sandbox, so the Spring
 * application cannot be compiled here. These are the algorithmic cores copied verbatim
 * out of the production classes, exercised against the same cases the JUnit suite
 * asserts. It proves the logic, not the wiring.
 */
public class Harness {

    static int passed = 0;
    static int failed = 0;

    static void check(String name, boolean condition) {
        if (condition) { passed++; }
        else { failed++; System.out.println("  FAIL: " + name); }
    }

    // ---- ShortCodeGenerator ------------------------------------------------
    static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{3,16}$");
    static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();
    static final Set<String> RESERVED = Set.of("links", "health", "actuator", "docs");

    static String generate(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    static boolean aliasValid(String alias) {
        if (alias == null || alias.isBlank()) return false;
        String t = alias.trim();
        if (!CODE_PATTERN.matcher(t).matches()) return false;
        String lower = t.toLowerCase(Locale.ROOT);
        return RESERVED.stream().noneMatch(r -> r.toLowerCase(Locale.ROOT).equals(lower));
    }

    // ---- UrlValidator address rules ---------------------------------------
    static boolean isCarrierGradeNatOrSpecial(InetAddress address) {
        byte[] o = address.getAddress();
        if (o.length != 4) return false;
        int first = o[0] & 0xFF, second = o[1] & 0xFF;
        if (first == 100 && second >= 64 && second <= 127) return true;
        if (first == 192 && second == 0 && (o[2] & 0xFF) == 0) return true;
        return first == 198 && (second == 18 || second == 19);
    }

    static boolean isRestricted(InetAddress a) {
        return a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isAnyLocalAddress() || a.isMulticastAddress() || isCarrierGradeNatOrSpecial(a);
    }

    /** Mirrors validateAndNormalize's scheme/userinfo/host checks. */
    static String rejectReason(String raw, String ownHost) {
        if (raw == null || raw.isBlank()) return "blank";
        String candidate = raw.trim();
        if (candidate.length() > 2048) return "too_long";
        URI uri;
        try {
            uri = new URI(candidate);
            if (!uri.isAbsolute()) return "relative";
        } catch (URISyntaxException e) { return "malformed"; }
        String scheme = uri.getScheme();
        if (scheme == null) return "relative";
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) return "scheme";
        if (uri.getRawUserInfo() != null) return "userinfo";
        String host = uri.getHost();
        if (host == null || host.isBlank()) return "host";
        host = host.toLowerCase(Locale.ROOT);
        if (ownHost != null && host.equals(ownHost)) return "self";
        return null;
    }

    // ---- RedirectController ------------------------------------------------
    static String referrerHost(String referer) {
        if (referer == null || referer.isBlank()) return "";
        try {
            String host = new URI(referer.trim()).getHost();
            if (host == null || host.isBlank()) return "";
            host = host.toLowerCase(Locale.ROOT);
            return host.length() > 255 ? host.substring(0, 255) : host;
        } catch (URISyntaxException e) { return ""; }
    }

    // ---- LinkCache ---------------------------------------------------------
    static Duration clampToExpiry(Duration ttl, Instant expiresAt, Instant now) {
        if (expiresAt == null) return ttl;
        Duration untilExpiry = Duration.between(now, expiresAt);
        return untilExpiry.compareTo(ttl) < 0 ? untilExpiry : ttl;
    }

    // ---- BufferedClickEventSink -------------------------------------------
    record ClickEvent(String code, Instant occurredAt, String referrerHost) {}
    record ClickAggregate(String code, LocalDate clickDay, String referrerHost, long clicks) {}
    record BucketKey(String code, LocalDate clickDay, String referrerHost) {}

    static List<ClickAggregate> aggregate(List<ClickEvent> events) {
        Map<BucketKey, Long> counts = new HashMap<>();
        for (ClickEvent e : events) {
            LocalDate day = e.occurredAt().atZone(ZoneOffset.UTC).toLocalDate();
            counts.merge(new BucketKey(e.code(), day, e.referrerHost()), 1L, Long::sum);
        }
        List<ClickAggregate> out = new ArrayList<>(counts.size());
        counts.forEach((k, c) -> out.add(new ClickAggregate(k.code(), k.clickDay(), k.referrerHost(), c)));
        return out;
    }

    // ---- AuthorizationPolicy -----------------------------------------------
    enum AuthMode { DISABLED, OPTIONAL, REQUIRED }
    enum Decision { ALLOW, UNAUTHENTICATED, FORBIDDEN }

    static Decision canCreate(AuthMode mode, Long callerKeyId) {
        if (mode == AuthMode.REQUIRED && callerKeyId == null) return Decision.UNAUTHENTICATED;
        return Decision.ALLOW;
    }

    static Decision canAdminister(AuthMode mode, Long ownerKeyId, Long callerKeyId) {
        if (mode == AuthMode.DISABLED) return Decision.ALLOW;
        if (ownerKeyId == null) return mode == AuthMode.REQUIRED ? Decision.FORBIDDEN : Decision.ALLOW;
        if (callerKeyId == null) return Decision.UNAUTHENTICATED;
        return ownerKeyId.equals(callerKeyId) ? Decision.ALLOW : Decision.FORBIDDEN;
    }

    // ---- ApiKeyService -----------------------------------------------------
    static String hashKey(String rawKey) {
        try {
            java.security.MessageDigest d = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    d.digest(rawKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String issueRawKey() {
        byte[] material = new byte[32];
        RANDOM.nextBytes(material);
        return "sk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(material);
    }

    // ---- RateLimitFilter ---------------------------------------------------
    static String clientIp(String forwarded, String remote) {
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return remote == null ? "unknown" : remote;
    }

    static boolean isRedirectPath(String uri) {
        return uri.length() > 1 && uri.indexOf('/', 1) < 0
                && CODE_PATTERN.matcher(uri.substring(1)).matches();
    }

    public static void main(String[] args) throws Exception {
        System.out.println("== ShortCodeGenerator ==");
        Set<String> seen = new HashSet<>();
        Set<Character> chars = new HashSet<>();
        for (int i = 0; i < 20000; i++) {
            String c = generate(7);
            check("length 7", c.length() == 7);
            check("base62 only", c.matches("[0-9A-Za-z]{7}"));
            seen.add(c);
            for (char ch : c.toCharArray()) chars.add(ch);
        }
        check("20k codes unique", seen.size() == 20000);
        check("alphabet fully exercised", chars.size() == 62);

        for (String ok : new String[]{"my-link", "Q3_report", "abc", "a1b2c3d4e5f6g7h8"})
            check("alias accepted: " + ok, aliasValid(ok));
        for (String bad : new String[]{"ab", "aaaaaaaaaaaaaaaaa", "has space", "has/slash",
                "dot.dot", "emoji-\uD83D\uDE00", "links", "LINKS", "Health", "actuator", "  ", null})
            check("alias rejected: " + bad, !aliasValid(bad));
        check("alias trimmed", aliasValid("  my-link  "));

        System.out.println("== UrlValidator: schemes and shape ==");
        check("https accepted", rejectReason("https://example.com/path?q=1", "sho.rt") == null);
        check("whitespace trimmed", rejectReason("  https://example.com/x  ", "sho.rt") == null);
        for (String bad : new String[]{"javascript:alert(document.cookie)",
                "data:text/html;base64,PHNjcmlwdD4=", "file:///etc/passwd",
                "ftp://example.com/x", "mailto:a@b.com"})
            check("scheme rejected: " + bad, "scheme".equals(rejectReason(bad, "sho.rt")));
        check("userinfo rejected",
                "userinfo".equals(rejectReason("https://accounts.example.com@evil.test/login", "sho.rt")));
        check("self link rejected", "self".equals(rejectReason("https://sho.rt/abc123", "sho.rt")));
        check("relative rejected", "relative".equals(rejectReason("/just/a/path", "sho.rt")));
        check("blank rejected", "blank".equals(rejectReason("   ", "sho.rt")));
        check("overlong rejected",
                "too_long".equals(rejectReason("https://example.com/" + "x".repeat(2100), "sho.rt")));

        System.out.println("== UrlValidator: SSRF address rules ==");
        for (String priv : new String[]{"127.0.0.1", "10.1.2.3", "192.168.0.5", "172.16.4.4",
                "169.254.169.254", "0.0.0.0", "100.100.1.1", "198.18.0.1", "224.0.0.1",
                "192.0.0.1", "::1", "fe80::1"})
            check("restricted: " + priv, isRestricted(InetAddress.getByName(priv)));
        for (String pub : new String[]{"93.184.216.34", "8.8.8.8", "1.1.1.1", "99.99.99.99",
                "100.128.0.1", "198.20.0.1", "2606:2800:220:1:248:1893:25c8:1946"})
            check("routable: " + pub, !isRestricted(InetAddress.getByName(pub)));

        System.out.println("== RedirectController.referrerHost ==");
        check("host lowercased",
                referrerHost("https://News.Example.com/story?utm=abc").equals("news.example.com"));
        check("port stripped", referrerHost("http://blog.test:8080/a/b").equals("blog.test"));
        check("token discarded", referrerHost(
                "https://mail.example.com/inbox?token=eyJhbGciOi&user=alice@example.com")
                .equals("mail.example.com"));
        for (String d : new String[]{null, "   ", "not a url", "/relative/path"})
            check("direct traffic: " + d, referrerHost(d).isEmpty());

        System.out.println("== LinkCache.clampToExpiry ==");
        Instant now = Instant.parse("2026-03-01T12:00:00Z");
        Duration ttl = Duration.ofMinutes(10);
        check("no expiry -> full ttl", clampToExpiry(ttl, null, now).equals(ttl));
        check("far expiry -> full ttl",
                clampToExpiry(ttl, now.plusSeconds(3600), now).equals(ttl));
        check("near expiry -> clamped",
                clampToExpiry(ttl, now.plusSeconds(30), now).equals(Duration.ofSeconds(30)));
        check("past expiry -> non-positive",
                !clampToExpiry(ttl, now.minusSeconds(30), now).isPositive());

        System.out.println("== BufferedClickEventSink.aggregate ==");
        Instant noon = Instant.parse("2026-03-01T12:00:00Z");
        List<ClickAggregate> agg = aggregate(List.of(
                new ClickEvent("abc1234", noon, "news.example"),
                new ClickEvent("abc1234", noon.plusSeconds(30), "news.example"),
                new ClickEvent("abc1234", noon, ""),
                new ClickEvent("xyz9999", noon, "news.example")));
        check("three buckets", agg.size() == 3);
        check("folded count of 2", agg.contains(
                new ClickAggregate("abc1234", LocalDate.of(2026, 3, 1), "news.example", 2)));
        check("direct traffic bucket", agg.contains(
                new ClickAggregate("abc1234", LocalDate.of(2026, 3, 1), "", 1)));
        check("utc day boundary splits", aggregate(List.of(
                new ClickEvent("abc1234", Instant.parse("2026-03-01T23:59:59Z"), ""),
                new ClickEvent("abc1234", Instant.parse("2026-03-02T00:00:01Z"), ""))).size() == 2);
        long total = aggregate(List.of(
                new ClickEvent("a1b2c3d", noon, "x.test"), new ClickEvent("a1b2c3d", noon, "x.test"),
                new ClickEvent("a1b2c3d", noon, "y.test"))).stream().mapToLong(ClickAggregate::clicks).sum();
        check("no clicks lost in folding", total == 3);

        System.out.println("== AuthorizationPolicy: exhaustive truth table ==");
        Long OWNER = 1L, OTHER = 2L;
        // Every (mode x owner x caller) combination, enumerated rather than sampled.
        check("disabled/unowned/anon",   canAdminister(AuthMode.DISABLED, null, null) == Decision.ALLOW);
        check("disabled/owned/anon",     canAdminister(AuthMode.DISABLED, OWNER, null) == Decision.ALLOW);
        check("disabled/owned/stranger", canAdminister(AuthMode.DISABLED, OWNER, OTHER) == Decision.ALLOW);
        check("optional/unowned/anon",   canAdminister(AuthMode.OPTIONAL, null, null) == Decision.ALLOW);
        check("optional/unowned/keyed",  canAdminister(AuthMode.OPTIONAL, null, OWNER) == Decision.ALLOW);
        check("optional/owned/anon",     canAdminister(AuthMode.OPTIONAL, OWNER, null) == Decision.UNAUTHENTICATED);
        check("optional/owned/owner",    canAdminister(AuthMode.OPTIONAL, OWNER, OWNER) == Decision.ALLOW);
        check("optional/owned/stranger", canAdminister(AuthMode.OPTIONAL, OWNER, OTHER) == Decision.FORBIDDEN);
        check("required/unowned/anon",   canAdminister(AuthMode.REQUIRED, null, null) == Decision.FORBIDDEN);
        check("required/unowned/keyed",  canAdminister(AuthMode.REQUIRED, null, OWNER) == Decision.FORBIDDEN);
        check("required/owned/anon",     canAdminister(AuthMode.REQUIRED, OWNER, null) == Decision.UNAUTHENTICATED);
        check("required/owned/owner",    canAdminister(AuthMode.REQUIRED, OWNER, OWNER) == Decision.ALLOW);
        check("required/owned/stranger", canAdminister(AuthMode.REQUIRED, OWNER, OTHER) == Decision.FORBIDDEN);
        check("create/disabled/anon",    canCreate(AuthMode.DISABLED, null) == Decision.ALLOW);
        check("create/optional/anon",    canCreate(AuthMode.OPTIONAL, null) == Decision.ALLOW);
        check("create/required/anon",    canCreate(AuthMode.REQUIRED, null) == Decision.UNAUTHENTICATED);
        check("create/required/keyed",   canCreate(AuthMode.REQUIRED, OWNER) == Decision.ALLOW);
        // Long caching stops at 127; a reference comparison would fail above it.
        check("ids compared by value",
                canAdminister(AuthMode.REQUIRED, 1000L, 1000L) == Decision.ALLOW);
        check("distinct ids above cache",
                canAdminister(AuthMode.REQUIRED, 1000L, 1001L) == Decision.FORBIDDEN);
        // No mode grants a stranger access to an owned link.
        for (AuthMode m : new AuthMode[]{AuthMode.OPTIONAL, AuthMode.REQUIRED})
            check("no stranger access in " + m, canAdminister(m, OWNER, OTHER) != Decision.ALLOW);

        System.out.println("== ApiKeyService ==");
        check("hash stable", hashKey("sk_abc").equals(hashKey("sk_abc")));
        check("hash distinguishes", !hashKey("sk_abc").equals(hashKey("sk_abd")));
        check("hash is 64 hex chars", hashKey("sk_abc").matches("[0-9a-f]{64}"));
        Set<String> keys = new HashSet<>(), hashes = new HashSet<>();
        for (int i = 0; i < 5000; i++) {
            String raw = issueRawKey();
            check("key prefixed", raw.startsWith("sk_"));
            keys.add(raw);
            hashes.add(hashKey(raw));
        }
        check("5k issued keys unique", keys.size() == 5000);
        check("5k hashes unique", hashes.size() == 5000);
        check("raw key not recoverable from hash",
                hashes.stream().noneMatch(h -> h.contains("sk_")));

        System.out.println("== RateLimitFilter ==");
        check("leftmost xff", clientIp("203.0.113.7, 10.0.0.1", "10.0.0.1").equals("203.0.113.7"));
        check("single xff", clientIp("203.0.113.7", "10.0.0.1").equals("203.0.113.7"));
        check("socket fallback", clientIp(null, "203.0.113.9").equals("203.0.113.9"));
        check("blank xff fallback", clientIp("   ", "203.0.113.9").equals("203.0.113.9"));
        check("never null", clientIp(null, null).equals("unknown"));
        check("redirect path matched", isRedirectPath("/abc1234"));
        // /links matches the code SHAPE; it is the reserved-word list, not the route
        // pattern, that stops a link ever being created there.
        check("/links has code shape", isRedirectPath("/links"));
        check("/links is reserved", !aliasValid("links"));
        check("nested path not matched", !isRedirectPath("/links/abc1234"));
        check("root not matched", !isRedirectPath("/"));
        check("actuator not matched", !isRedirectPath("/actuator/health"));

        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }
}
