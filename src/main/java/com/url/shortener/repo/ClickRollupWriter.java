package com.url.shortener.repo;

import com.url.shortener.analytics.ClickAggregate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/**
 * Batched, idempotent-by-construction writer for click rollups.
 *
 * <p>The {@code ON CONFLICT ... DO UPDATE} increment means concurrent flushes from
 * multiple instances converge without locking or read-modify-write races: the database
 * performs the addition, not the application.
 */
@Component
public class ClickRollupWriter {

    private static final String UPSERT = """
            INSERT INTO link_click_daily (code, click_day, referrer_host, clicks)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (code, click_day, referrer_host)
            DO UPDATE SET clicks = link_click_daily.clicks + EXCLUDED.clicks
            """;

    private final JdbcTemplate jdbcTemplate;

    public ClickRollupWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void upsertAll(List<ClickAggregate> aggregates) {
        if (aggregates.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(UPSERT, new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ClickAggregate a = aggregates.get(i);
                ps.setString(1, a.code());
                ps.setDate(2, Date.valueOf(a.clickDay()));
                ps.setString(3, a.referrerHost());
                ps.setLong(4, a.clicks());
            }

            @Override
            public int getBatchSize() {
                return aggregates.size();
            }
        });
    }
}
