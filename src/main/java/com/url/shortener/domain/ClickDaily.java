package com.url.shortener.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Pre-aggregated click counts, one row per (code, day, referrer host).
 * Written only by the analytics flush (via JDBC upsert); read via JPA.
 */
@Entity
@Table(name = "link_click_daily")
public class ClickDaily {

    @EmbeddedId
    private Key key;

    @Column(name = "clicks", nullable = false)
    private long clicks;

    protected ClickDaily() {
        // for JPA
    }

    public Key getKey() { return key; }

    public long getClicks() { return clicks; }

    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "code", nullable = false, length = 16)
        private String code;

        @Column(name = "click_day", nullable = false)
        private LocalDate clickDay;

        /** Empty string means "no referrer" (direct traffic). */
        @Column(name = "referrer_host", nullable = false, length = 255)
        private String referrerHost;

        protected Key() {
            // for JPA
        }

        public Key(String code, LocalDate clickDay, String referrerHost) {
            this.code = code;
            this.clickDay = clickDay;
            this.referrerHost = referrerHost;
        }

        public String getCode() { return code; }

        public LocalDate getClickDay() { return clickDay; }

        public String getReferrerHost() { return referrerHost; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key other)) return false;
            return Objects.equals(code, other.code)
                    && Objects.equals(clickDay, other.clickDay)
                    && Objects.equals(referrerHost, other.referrerHost);
        }

        @Override
        public int hashCode() {
            return Objects.hash(code, clickDay, referrerHost);
        }
    }
}
