package com.url.shortener.service;

import com.url.shortener.domain.Link;
import com.url.shortener.repo.LinkRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Transactional write boundary, deliberately separate from {@link LinkService}.
 *
 * <p>This split is not ceremony. A unique-constraint violation marks the surrounding
 * transaction rollback-only, so a retry loop that lives <em>inside</em> a transaction
 * cannot recover: every subsequent statement fails. Each insert attempt therefore needs
 * its own transaction, which means it must cross a proxy boundary into another bean.
 */
@Component
public class LinkWriter {

    private final LinkRepository links;

    public LinkWriter(LinkRepository links) {
        this.links = links;
    }

    /**
     * @return the saved link, or empty if the code was taken by a concurrent writer
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Link> tryInsert(Link link) {
        try {
            return Optional.of(links.saveAndFlush(link));
        } catch (DataIntegrityViolationException ex) {
            // The database is the arbiter of uniqueness; a check-then-insert in
            // application code would still race under concurrency.
            return Optional.empty();
        }
    }

    @Transactional(readOnly = true)
    public Optional<Link> findByCode(String code) {
        return links.findByCode(code);
    }

    @Transactional(readOnly = true)
    public Optional<Link> findByIdempotencyKey(String key) {
        return links.findByIdempotencyKey(key);
    }

    @Transactional
    public boolean deactivate(String code) {
        return links.findByCode(code)
                .filter(Link::isActive)
                .map(link -> {
                    link.deactivate();
                    return true;
                })
                .orElse(false);
    }

    @Transactional
    public int deactivateExpired(Instant now) {
        return links.deactivateExpired(now);
    }
}
