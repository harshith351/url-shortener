package com.url.shortener.service;

import com.url.shortener.config.AppProperties;
import com.url.shortener.config.AuthMode;
import com.url.shortener.domain.Link;
import com.url.shortener.error.AliasTakenException;
import com.url.shortener.error.CodeGenerationException;
import com.url.shortener.error.InvalidExpiryException;
import com.url.shortener.error.LinkExpiredException;
import com.url.shortener.error.ForbiddenException;
import com.url.shortener.error.IdempotencyKeyConflictException;
import com.url.shortener.error.LinkNotFoundException;
import com.url.shortener.error.UnauthenticatedException;
import com.url.shortener.repo.ClickDailyRepository;
import com.url.shortener.security.AuthorizationPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Application service for links. Owns the sequencing of validation, code allocation,
 * persistence and cache maintenance; delegates the transactional writes to
 * {@link LinkWriter} and all Redis interaction to {@link LinkCache}.
 */
@Service
public class LinkService {

    private static final Logger log = LoggerFactory.getLogger(LinkService.class);

    private final LinkWriter writer;
    private final LinkCache cache;
    private final UrlValidator urlValidator;
    private final ShortCodeGenerator codeGenerator;
    private final ClickDailyRepository clicks;
    private final AuthorizationPolicy authorization;
    private final AppProperties properties;
    private final Clock clock;

    public LinkService(LinkWriter writer,
                       LinkCache cache,
                       UrlValidator urlValidator,
                       ShortCodeGenerator codeGenerator,
                       ClickDailyRepository clicks,
                       AuthorizationPolicy authorization,
                       AppProperties properties,
                       Clock clock) {
        this.writer = writer;
        this.cache = cache;
        this.urlValidator = urlValidator;
        this.codeGenerator = codeGenerator;
        this.clicks = clicks;
        this.authorization = authorization;
        this.properties = properties;
        this.clock = clock;
    }

    /** Result of a create call; {@code reused} is true when idempotency returned an existing link. */
    public record CreateResult(Link link, boolean reused) {
    }

    public record CreateCommand(String longUrl,
                                String alias,
                                Instant expiresAt,
                                String idempotencyKey,
                                Long ownerKeyId) {

        /** Retained so existing callers compile unchanged; creates an unowned link. */
        public CreateCommand(String longUrl, String alias, Instant expiresAt, String idempotencyKey) {
            this(longUrl, alias, expiresAt, idempotencyKey, null);
        }
    }

    public CreateResult create(CreateCommand command) {
        Instant now = clock.instant();

        enforce(authorization.canCreate(mode(), command.ownerKeyId()));

        if (command.expiresAt() != null && !command.expiresAt().isAfter(now)) {
            throw new InvalidExpiryException("expiresAt must be in the future");
        }

        String normalizedUrl = urlValidator.validateAndNormalize(command.longUrl());

        // Replaying a create with the same key must never mint a second code.
        if (command.idempotencyKey() != null) {
            Optional<Link> existing = writer.findByIdempotencyKey(command.idempotencyKey());
            if (existing.isPresent()) {
                return new CreateResult(assertSameOwner(existing.get(), command.ownerKeyId()), true);
            }
        }

        if (command.alias() != null) {
            String alias = codeGenerator.validateAlias(command.alias());
            Link link = new Link(alias, normalizedUrl, now, command.expiresAt(),
                    command.idempotencyKey(), command.ownerKeyId());
            Optional<Link> saved = writer.tryInsert(link);
            if (saved.isEmpty()) {
                // Could be the alias or a racing idempotent replay; check the latter first.
                return resolveInsertConflict(command, alias);
            }
            cache.evict(alias); // clear any negative cache entry from a prior 404
            return new CreateResult(saved.get(), false);
        }

        int attempts = properties.code().maxGenerationAttempts();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            String code = codeGenerator.generate();
            Link link = new Link(code, normalizedUrl, now, command.expiresAt(),
                    command.idempotencyKey(), command.ownerKeyId());
            Optional<Link> saved = writer.tryInsert(link);
            if (saved.isPresent()) {
                cache.evict(code);
                return new CreateResult(saved.get(), false);
            }
            if (command.idempotencyKey() != null) {
                Optional<Link> replay = writer.findByIdempotencyKey(command.idempotencyKey());
                if (replay.isPresent()) {
                    return new CreateResult(assertSameOwner(replay.get(), command.ownerKeyId()), true);
                }
            }
            log.info("Short code collision on attempt {} of {}", attempt, attempts);
        }
        // Reaching here means either genuine key-space pressure or a persistent write
        // fault. Both are operational problems, so this surfaces as 503, not 500.
        throw new CodeGenerationException(attempts);
    }

    private CreateResult resolveInsertConflict(CreateCommand command, String alias) {
        if (command.idempotencyKey() != null) {
            Optional<Link> replay = writer.findByIdempotencyKey(command.idempotencyKey());
            if (replay.isPresent()) {
                return new CreateResult(assertSameOwner(replay.get(), command.ownerKeyId()), true);
            }
        }
        throw new AliasTakenException(alias);
    }

    /**
     * Hot path. Returns the destination for a code, consulting the cache first.
     *
     * @throws LinkNotFoundException if no such code exists
     * @throws LinkExpiredException  if the code existed but is retired or past expiry
     */
    public String resolve(String code) {
        Instant now = clock.instant();

        Optional<LinkCache.Lookup> cached = cache.lookup(code);
        if (cached.isPresent()) {
            LinkCache.Lookup lookup = cached.get();
            if (!lookup.exists()) {
                throw new LinkNotFoundException(code);
            }
            return lookup.longUrl();
        }

        Link link = writer.findByCode(code).orElse(null);
        if (link == null) {
            cache.putMissing(code);
            throw new LinkNotFoundException(code);
        }
        if (!link.isResolvableAt(now)) {
            // Not negatively cached: an expired code is a distinct 410 answer, and
            // caching it as "missing" would downgrade the response to 404.
            throw new LinkExpiredException(code);
        }
        cache.putFound(code, link.getLongUrl(), link.getExpiresAt(), now);
        return link.getLongUrl();
    }

    public Link get(String code) {
        return writer.findByCode(code).orElseThrow(() -> new LinkNotFoundException(code));
    }

    /** Retained for callers that predate ownership; administers as an anonymous caller. */
    public void delete(String code) {
        delete(code, null);
    }

    public void delete(String code, Long callerKeyId) {
        Link link = get(code);
        enforce(authorization.canAdminister(mode(), link.getOwnerKeyId(), callerKeyId));
        if (!writer.deactivate(code)) {
            throw new LinkNotFoundException(code);
        }
        // Order matters: the row is already inactive, so a failed eviction degrades to
        // "serves for up to one TTL" rather than "resurrects a deleted link".
        cache.evict(code);
    }

    /** Retained for callers that predate ownership; reads as an anonymous caller. */
    public Stats stats(String code, int days, int referrerLimit) {
        return stats(code, days, referrerLimit, null);
    }

    public Stats stats(String code, int days, int referrerLimit, Long callerKeyId) {
        Link link = get(code);
        enforce(authorization.canAdminister(mode(), link.getOwnerKeyId(), callerKeyId));
        LocalDate from = LocalDate.now(clock.withZone(ZoneOffset.UTC)).minusDays(days - 1L);
        return new Stats(
                link,
                clicks.totalClicks(code),
                clicks.dailyCounts(code, from),
                clicks.topReferrers(code, PageRequest.of(0, referrerLimit)));
    }

    private AuthMode mode() {
        return properties.auth().mode();
    }

    private static void enforce(AuthorizationPolicy.Decision decision) {
        switch (decision) {
            case UNAUTHENTICATED -> throw new UnauthenticatedException();
            case FORBIDDEN -> throw new ForbiddenException();
            case ALLOW -> { /* proceed */ }
        }
    }

    /**
     * An idempotent replay must never hand back a link belonging to someone else, which a
     * guessable key such as "order-1" would otherwise allow across tenants.
     */
    private static Link assertSameOwner(Link existing, Long callerKeyId) {
        if (java.util.Objects.equals(existing.getOwnerKeyId(), callerKeyId)) {
            return existing;
        }
        throw new IdempotencyKeyConflictException();
    }

    public record Stats(Link link,
                        long totalClicks,
                        List<ClickDailyRepository.DailyCount> daily,
                        List<ClickDailyRepository.ReferrerCount> topReferrers) {
    }
}
