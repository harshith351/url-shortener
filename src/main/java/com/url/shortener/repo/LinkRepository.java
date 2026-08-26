package com.url.shortener.repo;

import com.url.shortener.domain.Link;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface LinkRepository extends JpaRepository<Link, Long> {

    Optional<Link> findByCode(String code);

    Optional<Link> findByIdempotencyKey(String idempotencyKey);

    /**
     * Lazy expiry sweeper. Redirects already reject expired links at read time; this
     * only keeps the table honest so operators and reports see accurate state.
     */
    @Modifying
    @Query("update Link l set l.active = false "
            + "where l.active = true and l.expiresAt is not null and l.expiresAt < :now")
    int deactivateExpired(@Param("now") Instant now);
}
