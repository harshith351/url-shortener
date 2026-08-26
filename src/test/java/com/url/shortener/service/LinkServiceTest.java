package com.url.shortener.service;

import com.url.shortener.TestProperties;
import com.url.shortener.domain.Link;
import com.url.shortener.error.AliasTakenException;
import com.url.shortener.error.CodeGenerationException;
import com.url.shortener.error.InvalidExpiryException;
import com.url.shortener.error.LinkExpiredException;
import com.url.shortener.error.LinkNotFoundException;
import com.url.shortener.repo.ClickDailyRepository;
import com.url.shortener.security.AuthorizationPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LinkServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final String URL = "https://example.com/target";

    private LinkWriter writer;
    private LinkCache cache;
    private UrlValidator urlValidator;
    private ShortCodeGenerator codeGenerator;
    private ClickDailyRepository clicks;
    private LinkService service;

    @BeforeEach
    void setUp() {
        writer = mock(LinkWriter.class);
        cache = mock(LinkCache.class);
        urlValidator = mock(UrlValidator.class);
        codeGenerator = mock(ShortCodeGenerator.class);
        clicks = mock(ClickDailyRepository.class);
        when(urlValidator.validateAndNormalize(anyString())).thenAnswer(inv -> inv.getArgument(0));
        service = new LinkService(writer, cache, urlValidator, codeGenerator, clicks,
                new AuthorizationPolicy(), TestProperties.defaults(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Link link(String code) {
        return new Link(code, URL, NOW, null, null);
    }

    @Nested
    class Creation {

        @Test
        void persistsAGeneratedCode() {
            when(codeGenerator.generate()).thenReturn("abc1234");
            when(writer.tryInsert(any())).thenAnswer(inv -> Optional.of(inv.getArgument(0)));

            LinkService.CreateResult result =
                    service.create(new LinkService.CreateCommand(URL, null, null, null));

            assertThat(result.reused()).isFalse();
            assertThat(result.link().getCode()).isEqualTo("abc1234");
            assertThat(result.link().getLongUrl()).isEqualTo(URL);
        }

        @Test
        @DisplayName("retries with a fresh code when the database rejects a duplicate")
        void retriesOnCollision() {
            when(codeGenerator.generate()).thenReturn("taken1", "free123");
            when(writer.tryInsert(any()))
                    .thenReturn(Optional.empty())
                    .thenAnswer(inv -> Optional.of(inv.getArgument(0)));

            LinkService.CreateResult result =
                    service.create(new LinkService.CreateCommand(URL, null, null, null));

            assertThat(result.link().getCode()).isEqualTo("free123");
            verify(writer, times(2)).tryInsert(any());
        }

        @Test
        @DisplayName("gives up with 503 rather than looping forever under key-space pressure")
        void failsAfterExhaustingAttempts() {
            when(codeGenerator.generate()).thenReturn("aaaaaaa");
            when(writer.tryInsert(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    service.create(new LinkService.CreateCommand(URL, null, null, null)))
                    .isInstanceOf(CodeGenerationException.class);

            verify(writer, times(3)).tryInsert(any()); // max-generation-attempts in TestProperties
        }

        @Test
        @DisplayName("a replayed idempotency key returns the original link and mints nothing")
        void idempotentReplayReturnsExistingLink() {
            Link existing = link("orig123");
            when(writer.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));

            LinkService.CreateResult result =
                    service.create(new LinkService.CreateCommand(URL, null, null, "key-1"));

            assertThat(result.reused()).isTrue();
            assertThat(result.link()).isSameAs(existing);
            verify(writer, never()).tryInsert(any());
            verifyNoInteractions(codeGenerator);
        }

        @Test
        @DisplayName("a concurrent duplicate that loses the insert race still returns the winner")
        void concurrentIdempotentReplayResolvesToTheWinner() {
            Link winner = link("orig123");
            when(codeGenerator.generate()).thenReturn("mine123");
            when(writer.tryInsert(any())).thenReturn(Optional.empty());
            when(writer.findByIdempotencyKey("key-1"))
                    .thenReturn(Optional.empty())   // checked before the insert
                    .thenReturn(Optional.of(winner)); // the racing request committed first

            LinkService.CreateResult result =
                    service.create(new LinkService.CreateCommand(URL, null, null, "key-1"));

            assertThat(result.reused()).isTrue();
            assertThat(result.link()).isSameAs(winner);
        }

        @Test
        void rejectsAnAliasThatIsAlreadyTaken() {
            when(codeGenerator.validateAlias("my-link")).thenReturn("my-link");
            when(writer.tryInsert(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    service.create(new LinkService.CreateCommand(URL, "my-link", null, null)))
                    .isInstanceOf(AliasTakenException.class);
        }

        @Test
        @DisplayName("does not retry a custom alias -- a different code would not be what was asked for")
        void doesNotRetryCustomAlias() {
            when(codeGenerator.validateAlias("my-link")).thenReturn("my-link");
            when(writer.tryInsert(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    service.create(new LinkService.CreateCommand(URL, "my-link", null, null)))
                    .isInstanceOf(AliasTakenException.class);

            verify(writer, times(1)).tryInsert(any());
        }

        @Test
        void rejectsExpiryInThePast() {
            assertThatThrownBy(() -> service.create(
                    new LinkService.CreateCommand(URL, null, NOW.minusSeconds(1), null)))
                    .isInstanceOf(InvalidExpiryException.class);

            verifyNoInteractions(writer);
        }

        @Test
        @DisplayName("clears any negative cache entry so a freshly created code is live immediately")
        void evictsNegativeCacheEntryOnCreate() {
            when(codeGenerator.generate()).thenReturn("abc1234");
            when(writer.tryInsert(any())).thenAnswer(inv -> Optional.of(inv.getArgument(0)));

            service.create(new LinkService.CreateCommand(URL, null, null, null));

            verify(cache).evict("abc1234");
        }
    }

    @Nested
    class Resolution {

        @Test
        void servesFromCacheWithoutTouchingTheDatabase() {
            when(cache.lookup("abc1234"))
                    .thenReturn(Optional.of(new LinkCache.Lookup(true, URL)));

            assertThat(service.resolve("abc1234")).isEqualTo(URL);
            verify(writer, never()).findByCode(anyString());
        }

        @Test
        @DisplayName("a cached miss short-circuits to 404 without a database round trip")
        void negativeCacheShortCircuits() {
            when(cache.lookup("nope123"))
                    .thenReturn(Optional.of(new LinkCache.Lookup(false, null)));

            assertThatThrownBy(() -> service.resolve("nope123"))
                    .isInstanceOf(LinkNotFoundException.class);
            verify(writer, never()).findByCode(anyString());
        }

        @Test
        void populatesTheCacheOnAMiss() {
            when(cache.lookup("abc1234")).thenReturn(Optional.empty());
            when(writer.findByCode("abc1234")).thenReturn(Optional.of(link("abc1234")));

            assertThat(service.resolve("abc1234")).isEqualTo(URL);
            verify(cache).putFound("abc1234", URL, null, NOW);
        }

        @Test
        void negativelyCachesUnknownCodes() {
            when(cache.lookup("nope123")).thenReturn(Optional.empty());
            when(writer.findByCode("nope123")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.resolve("nope123"))
                    .isInstanceOf(LinkNotFoundException.class);
            verify(cache).putMissing("nope123");
        }

        @Test
        @DisplayName("an expired link is 410 and is never cached as missing")
        void expiredLinkIsGoneAndNotNegativelyCached() {
            Link expired = new Link("old1234", URL, NOW.minusSeconds(7200), NOW.minusSeconds(60), null);
            when(cache.lookup("old1234")).thenReturn(Optional.empty());
            when(writer.findByCode("old1234")).thenReturn(Optional.of(expired));

            assertThatThrownBy(() -> service.resolve("old1234"))
                    .isInstanceOf(LinkExpiredException.class);
            verify(cache, never()).putMissing(anyString());
            verify(cache, never()).putFound(anyString(), anyString(), any(), any());
        }

        @Test
        @DisplayName("a soft-deleted link is 410, not a redirect")
        void deactivatedLinkIsGone() {
            Link retired = link("old1234");
            retired.deactivate();
            when(cache.lookup("old1234")).thenReturn(Optional.empty());
            when(writer.findByCode("old1234")).thenReturn(Optional.of(retired));

            assertThatThrownBy(() -> service.resolve("old1234"))
                    .isInstanceOf(LinkExpiredException.class);
        }
    }

    @Nested
    class Deletion {

        @Test
        void deactivatesThenEvicts() {
            when(writer.findByCode("abc1234")).thenReturn(Optional.of(link("abc1234")));
            when(writer.deactivate("abc1234")).thenReturn(true);

            service.delete("abc1234");

            verify(writer).deactivate("abc1234");
            verify(cache).evict("abc1234");
        }

        @Test
        void deletingAnUnknownCodeIs404() {
            when(writer.findByCode("nope123")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.delete("nope123"))
                    .isInstanceOf(LinkNotFoundException.class);
            verify(cache, never()).evict(eq("nope123"));
        }
    }
}
