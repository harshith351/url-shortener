package com.url.shortener.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end coverage against the real datastores.
 *
 * <p>Unit tests prove the branching logic. They cannot prove that the Flyway schema
 * matches the entity mapping, that the {@code ON CONFLICT} upsert is valid SQL, or that
 * the redirect route does not shadow {@code /links}. Those are exactly the defects that
 * reach production, so this test runs against Postgres and Redis rather than fakes.
 *
 * <p>Two deliberate choices:
 * <ul>
 *   <li>A raw {@link HttpClient} with {@code NEVER} redirect following, because the
 *       assertion under test <em>is</em> the 302 and its headers.</li>
 *   <li>{@code block-private-networks=false}, so the suite needs no DNS and no egress.
 *       SSRF rules are covered exhaustively in {@code UrlValidatorTest} with a stubbed
 *       resolver, which is both faster and deterministic.</li>
 * </ul>
 *
 * <p>Requires a running Docker daemon. Bound to the {@code verify} phase via failsafe.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class LinkApiIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("app.security.block-private-networks", () -> "false");
        registry.add("app.auth.mode", () -> "OPTIONAL");
        registry.add("app.auth.admin-token", () -> "it-admin-token");
        registry.add("app.analytics.flush-interval", () -> "PT1S");
    }

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    int port;

    @Test
    @DisplayName("a created link redirects, and the click shows up in stats")
    void createRedirectAndCount() throws Exception {
        JsonNode created = json(post("/links", """
                {"url": "https://target.test/docs/guide?ref=email"}
                """, null));
        String code = created.get("code").asText();

        assertThat(code).matches("[0-9A-Za-z]{7}");
        assertThat(created.get("shortUrl").asText()).endsWith("/" + code);

        HttpResponse<String> redirect = get("/" + code, "https://news.test/story");
        assertThat(redirect.statusCode()).isEqualTo(302);
        assertThat(redirect.headers().firstValue("Location"))
                .contains("https://target.test/docs/guide?ref=email");
        // Without no-store the browser would cache the hop and every later click is invisible.
        assertThat(redirect.headers().firstValue("Cache-Control").orElse(""))
                .contains("no-store");

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            JsonNode stats = json(get("/links/" + code + "/stats", null));
            assertThat(stats.get("totalClicks").asLong()).isEqualTo(1L);
            assertThat(stats.get("topReferrers").get(0).get("host").asText()).isEqualTo("news.test");
        });
    }

    @Test
    @DisplayName("a custom alias is honoured and cannot be claimed twice")
    void customAliasIsUniqueAcrossRequests() throws Exception {
        String alias = "guide-" + System.nanoTime() % 100000;
        HttpResponse<String> first = post("/links",
                "{\"url\": \"https://target.test/a\", \"alias\": \"" + alias + "\"}", null);
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(json(first).get("code").asText()).isEqualTo(alias);

        HttpResponse<String> second = post("/links",
                "{\"url\": \"https://target.test/b\", \"alias\": \"" + alias + "\"}", null);
        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(json(second).get("error").asText()).isEqualTo("alias_taken");
    }

    @Test
    @DisplayName("reserved words cannot be claimed as aliases")
    void reservedAliasIsRejected() throws Exception {
        HttpResponse<String> response = post("/links",
                "{\"url\": \"https://target.test/a\", \"alias\": \"actuator\"}", null);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).get("error").asText()).isEqualTo("invalid_alias");
    }

    @Test
    @DisplayName("replaying an idempotency key returns the original link, not a new code")
    void idempotentCreate() throws Exception {
        String key = "key-" + System.nanoTime();
        HttpResponse<String> first = post("/links",
                "{\"url\": \"https://target.test/idem\"}", key);
        HttpResponse<String> second = post("/links",
                "{\"url\": \"https://target.test/idem\"}", key);

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(json(second).get("code").asText()).isEqualTo(json(first).get("code").asText());
    }

    @Test
    @DisplayName("an unknown code is 404 and a retired code is 410")
    void unknownIs404AndDeletedIs410() throws Exception {
        assertThat(get("/zzzzzzz", null).statusCode()).isEqualTo(404);

        String code = json(post("/links", "{\"url\": \"https://target.test/gone\"}", null))
                .get("code").asText();
        assertThat(get("/" + code, null).statusCode()).isEqualTo(302);

        assertThat(delete("/links/" + code).statusCode()).isEqualTo(204);
        // Proves cache eviction on delete: a stale entry would still return 302 here.
        assertThat(get("/" + code, null).statusCode()).isEqualTo(410);
    }

    @Test
    @DisplayName("an expired link stops resolving without any sweeper run")
    void expiredLinkIsGone() throws Exception {
        String expiresAt = java.time.Instant.now().plusSeconds(2).toString();
        String code = json(post("/links",
                "{\"url\": \"https://target.test/short-lived\", \"expiresAt\": \"" + expiresAt + "\"}",
                null)).get("code").asText();

        assertThat(get("/" + code, null).statusCode()).isEqualTo(302);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(get("/" + code, null).statusCode()).isEqualTo(410));
    }

    @Test
    @DisplayName("script and non-http schemes are refused at the boundary")
    void rejectsDangerousSchemes() throws Exception {
        HttpResponse<String> response =
                post("/links", "{\"url\": \"javascript:alert(1)\"}", null);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).get("error").asText()).isEqualTo("invalid_url");
    }

    @Test
    @DisplayName("the redirect route does not shadow the management API")
    void managementRoutesAreNotSwallowedByTheRedirectMapping() throws Exception {
        assertThat(get("/actuator/health", null).statusCode()).isEqualTo(200);
        assertThat(get("/links/zzzzzzz", null).statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("an owned link is administrable by its owner and by nobody else")
    void ownershipIsEnforcedEndToEnd() throws Exception {
        String owner = issueKey("owner");
        String stranger = issueKey("stranger");

        String code = json(post("/links", "{\"url\": \"https://target.test/owned\"}", null, owner))
                .get("code").asText();

        // Anyone may follow the link; ownership governs administration, not redirection.
        assertThat(get("/" + code, null).statusCode()).isEqualTo(302);

        assertThat(getWithKey("/links/" + code + "/stats", owner).statusCode()).isEqualTo(200);
        assertThat(getWithKey("/links/" + code + "/stats", stranger).statusCode()).isEqualTo(403);
        assertThat(getWithKey("/links/" + code + "/stats", null).statusCode()).isEqualTo(401);
        assertThat(deleteWithKey("/links/" + code, stranger).statusCode()).isEqualTo(403);
        assertThat(deleteWithKey("/links/" + code, owner).statusCode()).isEqualTo(204);
    }

    @Test
    @DisplayName("an unowned link stays administrable while auth is optional")
    void unownedLinksAreGrandfathered() throws Exception {
        String code = json(post("/links", "{\"url\": \"https://target.test/legacy\"}", null, null))
                .get("code").asText();

        assertThat(getWithKey("/links/" + code + "/stats", null).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("an invalid key is rejected rather than treated as anonymous")
    void invalidKeyIsRejected() throws Exception {
        HttpResponse<String> response =
                post("/links", "{\"url\": \"https://target.test/x\"}", null, "sk_not_a_real_key");
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("an idempotency key cannot be used to read another owner's link")
    void idempotencyKeysDoNotCrossOwners() throws Exception {
        String owner = issueKey("owner-idem");
        String stranger = issueKey("stranger-idem");
        String idem = "order-" + System.nanoTime();

        assertThat(post("/links", "{\"url\": \"https://target.test/mine\"}", idem, owner)
                .statusCode()).isEqualTo(201);
        HttpResponse<String> attempt =
                post("/links", "{\"url\": \"https://target.test/theirs\"}", idem, stranger);

        assertThat(attempt.statusCode()).isEqualTo(409);
        assertThat(json(attempt).get("error").asText()).isEqualTo("idempotency_key_conflict");
    }

    @Test
    @DisplayName("key issuance refuses a wrong or missing admin token")
    void keyIssuanceIsGated() throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url("/api-keys")))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\": \"sneaky\"}"));
        assertThat(CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(403);

        assertThat(CLIENT.send(builder.header("X-Admin-Token", "wrong").build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
    }

    // --- helpers -------------------------------------------------------------

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpResponse<String> post(String path, String body, String idempotencyKey)
            throws IOException, InterruptedException {
        return post(path, body, idempotencyKey, null);
    }

    private HttpResponse<String> post(String path, String body, String idempotencyKey, String apiKey)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url(path)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        if (apiKey != null) {
            builder.header("X-API-Key", apiKey);
        }
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String issueKey(String name) throws IOException, InterruptedException {
        HttpResponse<String> response = CLIENT.send(
                HttpRequest.newBuilder(URI.create(url("/api-keys")))
                        .header("Content-Type", "application/json")
                        .header("X-Admin-Token", "it-admin-token")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"name\": \"" + name + "-" + System.nanoTime() + "\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
        return json(response).get("apiKey").asText();
    }

    private HttpResponse<String> getWithKey(String path, String apiKey)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url(path))).GET();
        if (apiKey != null) {
            builder.header("X-API-Key", apiKey);
        }
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> deleteWithKey(String path, String apiKey)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url(path))).DELETE();
        if (apiKey != null) {
            builder.header("X-API-Key", apiKey);
        }
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String referer)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url(path))).GET();
        if (referer != null) {
            builder.header("Referer", referer);
        }
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path) throws IOException, InterruptedException {
        return CLIENT.send(HttpRequest.newBuilder(URI.create(url(path))).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) throws IOException {
        return JSON.readTree(response.body());
    }
}
