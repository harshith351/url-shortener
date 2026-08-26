package com.url.shortener.repo;

import com.url.shortener.domain.ClickDaily;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * Read side of the analytics rollup. Writes go through {@link ClickRollupWriter}
 * because they need a batched database-level upsert that JPA cannot express.
 */
public interface ClickDailyRepository extends JpaRepository<ClickDaily, ClickDaily.Key> {

    @Query("select coalesce(sum(c.clicks), 0) from ClickDaily c where c.key.code = :code")
    long totalClicks(@Param("code") String code);

    @Query("select c.key.clickDay as clickDay, sum(c.clicks) as clicks "
            + "from ClickDaily c "
            + "where c.key.code = :code and c.key.clickDay >= :from "
            + "group by c.key.clickDay "
            + "order by c.key.clickDay")
    List<DailyCount> dailyCounts(@Param("code") String code, @Param("from") LocalDate from);

    @Query("select c.key.referrerHost as host, sum(c.clicks) as clicks "
            + "from ClickDaily c "
            + "where c.key.code = :code "
            + "group by c.key.referrerHost "
            + "order by sum(c.clicks) desc")
    List<ReferrerCount> topReferrers(@Param("code") String code, Pageable pageable);

    /** Projection: clicks for one day. */
    interface DailyCount {
        LocalDate getClickDay();

        long getClicks();
    }

    /** Projection: clicks by referring host ("" == direct traffic). */
    interface ReferrerCount {
        String getHost();

        long getClicks();
    }
}
