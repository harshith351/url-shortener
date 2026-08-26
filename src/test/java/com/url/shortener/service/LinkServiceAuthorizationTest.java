package com.url.shortener.service;

import com.url.shortener.TestProperties;
import com.url.shortener.config.AppProperties;
import com.url.shortener.config.AuthMode;
import com.url.shortener.domain.Link;
import com.url.shortener.error.ForbiddenException;
import com.url.shortener.error.IdempotencyKeyConflictException;
import com.url.shortener.error.UnauthenticatedException;
import com.url.shortener.repo.ClickDailyRepository;
import com.url.shortener.security.AuthorizationPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ownership enforcement at the service boundary.
 *
 * <p>{@code AuthorizationPolicyTest} proves the decision function. This proves the service
 * actually consults it on every administrative path -- the failure mode being a correct
 * policy that some endpoint simply never calls.
 */
class LinkServiceAuthorizationTest {

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final String URL = "https://example.com/target";
    private static final Long OWNER = 1L;
    private static final Long OTHER = 2L;

    private final LinkWriter writer = mock(LinkWriter.class);
    private final LinkCache cache = mock(LinkCache.class);
    private final UrlValidator urlValidator = mock(UrlValidator.class);
    private final ShortCodeGenerator codeGenerator = mock(ShortCodeGenerator.class);
    private final ClickDailyRepository clicks = mock(ClickDailyRepository.class);

    private LinkService service(AuthMode mode) {
        AppProperties properties = TestProperties.withAuth(mode);
        when(urlValidator.validateAndNormalize(anyString())).thenAnswer(inv -> inv.getArgument(0));
        return new LinkService(writer, cache, urlValidator, codeGenerator, clicks,
                new AuthorizationPolicy(), properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Link ownedBy(Long ownerKeyId) {
        return new Link("abc1234", URL, NOW, null, null, ownerKeyId);
    }

    @Test
    @DisplayName("a new link is stamped with the creating key")
    void createRecordsTheOwner() {
        when(codeGenerator.generate()).thenReturn("abc1234");
        when(writer.tryInsert(any())).thenAnswer(inv -> Optional.of(inv.getArgument(0)));

        LinkService.CreateResult result = service(AuthMode.OPTIONAL).create(
                new LinkService.CreateCommand(URL, null, null, null, OWNER));

        assertThat(result.link().getOwnerKeyId()).isEqualTo(OWNER);
    }

    @Test
    @DisplayName("creation without a key is refused only once the rollout completes")
    void creationRequiresAKeyInRequiredMode() {
        assertThatThrownBy(() -> service(AuthMode.REQUIRED).create(
                new LinkService.CreateCommand(URL, null, null, null, null)))
                .isInstanceOf(UnauthenticatedException.class);

        when(codeGenerator.generate()).thenReturn("abc1234");
        when(writer.tryInsert(any())).thenAnswer(inv -> Optional.of(inv.getArgument(0)));
        assertThatCode(() -> service(AuthMode.OPTIONAL).create(
                new LinkService.CreateCommand(URL, null, null, null, null)))
                .doesNotThrowAnyException();
    }

    @Test
    void ownerMayReadStats() {
        when(writer.findByCode("abc1234")).thenReturn(Optional.of(ownedBy(OWNER)));
        when(clicks.totalClicks("abc1234")).thenReturn(7L);

        assertThat(service(AuthMode.OPTIONAL).stats("abc1234", 7, 5, OWNER).totalClicks())
                .isEqualTo(7L);
    }

    @Test
    @DisplayName("another tenant's key cannot read stats")
    void strangerMayNotReadStats() {
        when(writer.findByCode("abc1234")).thenReturn(Optional.of(ownedBy(OWNER)));

        assertThatThrownBy(() -> service(AuthMode.OPTIONAL).stats("abc1234", 7, 5, OTHER))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("an anonymous caller cannot read stats for an owned link")
    void anonymousMayNotReadOwnedStats() {
        when(writer.findByCode("abc1234")).thenReturn(Optional.of(ownedBy(OWNER)));

        assertThatThrownBy(() -> service(AuthMode.OPTIONAL).stats("abc1234", 7, 5, null))
                .isInstanceOf(UnauthenticatedException.class);
    }

    @Test
    @DisplayName("deletion is refused before the link is touched, not after")
    void strangerMayNotDelete() {
        when(writer.findByCode("abc1234")).thenReturn(Optional.of(ownedBy(OWNER)));

        assertThatThrownBy(() -> service(AuthMode.OPTIONAL).delete("abc1234", OTHER))
                .isInstanceOf(ForbiddenException.class);

        verify(writer, never()).deactivate(anyString());
        verify(cache, never()).evict(anyString());
    }

    @Test
    @DisplayName("links created before ownership stay administrable during the rollout")
    void legacyLinksAreGrandfatheredInOptionalMode() {
        when(writer.findByCode("abc1234")).thenReturn(Optional.of(ownedBy(null)));
        when(writer.deactivate("abc1234")).thenReturn(true);

        assertThatCode(() -> service(AuthMode.OPTIONAL).delete("abc1234", null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an unbackfilled link becomes unadministrable in REQUIRED mode")
    void legacyLinksAreRefusedInRequiredMode() {
        when(writer.findByCode("abc1234")).thenReturn(Optional.of(ownedBy(null)));

        assertThatThrownBy(() -> service(AuthMode.REQUIRED).delete("abc1234", OWNER))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("a guessed idempotency key cannot leak another tenant's link")
    void idempotentReplayIsScopedToTheOwner() {
        // Idempotency keys are globally unique, inherited from the single-tenant schema.
        // Without this guard, POSTing with Idempotency-Key: order-1 would return whatever
        // link another tenant happened to create with the same key.
        Link theirs = new Link("abc1234", URL, NOW, null, "order-1", OWNER);
        when(writer.findByIdempotencyKey("order-1")).thenReturn(Optional.of(theirs));

        assertThatThrownBy(() -> service(AuthMode.OPTIONAL).create(
                new LinkService.CreateCommand(URL, null, null, "order-1", OTHER)))
                .isInstanceOf(IdempotencyKeyConflictException.class);
    }

    @Test
    void ownerStillGetsTheirOwnIdempotentReplay() {
        Link theirs = new Link("abc1234", URL, NOW, null, "order-1", OWNER);
        when(writer.findByIdempotencyKey("order-1")).thenReturn(Optional.of(theirs));

        LinkService.CreateResult result = service(AuthMode.OPTIONAL).create(
                new LinkService.CreateCommand(URL, null, null, "order-1", OWNER));

        assertThat(result.reused()).isTrue();
        assertThat(result.link()).isSameAs(theirs);
    }

    @Test
    @DisplayName("redirects are never gated on ownership in any mode")
    void resolutionIgnoresOwnership() {
        when(cache.lookup("abc1234")).thenReturn(Optional.empty());
        when(writer.findByCode("abc1234")).thenReturn(Optional.of(ownedBy(OWNER)));

        assertThat(service(AuthMode.REQUIRED).resolve("abc1234")).isEqualTo(URL);
    }
}
