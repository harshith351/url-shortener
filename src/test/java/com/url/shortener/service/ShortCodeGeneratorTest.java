package com.url.shortener.service;

import com.url.shortener.TestProperties;
import com.url.shortener.error.InvalidAliasException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShortCodeGeneratorTest {

    private final ShortCodeGenerator generator = new ShortCodeGenerator(TestProperties.defaults());

    @Test
    @DisplayName("generates codes of the configured length using only base62 characters")
    void generatesWellFormedCodes() {
        for (int i = 0; i < 500; i++) {
            String code = generator.generate();
            assertThat(code).hasSize(7);
            assertThat(code).matches("[0-9A-Za-z]{7}");
        }
    }

    @Test
    @DisplayName("does not repeat within a large sample")
    void codesAreEffectivelyUnique() {
        // Not a uniqueness proof -- the database constraint is that. This catches the
        // catastrophic failure mode of a mis-seeded or constant generator.
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 20_000; i++) {
            seen.add(generator.generate());
        }
        assertThat(seen).hasSize(20_000);
    }

    @Test
    @DisplayName("uses every character of the alphabet over a large sample")
    void alphabetIsFullyExercised() {
        Set<Character> seen = new HashSet<>();
        for (int i = 0; i < 20_000; i++) {
            for (char c : generator.generate().toCharArray()) {
                seen.add(c);
            }
        }
        assertThat(seen).hasSize(ShortCodeGenerator.ALPHABET.length());
    }

    @ParameterizedTest
    @ValueSource(strings = {"my-link", "Q3_report", "abc", "a1b2c3d4e5f6g7h8"})
    void acceptsWellFormedAliases(String alias) {
        assertThat(generator.validateAlias(alias)).isEqualTo(alias);
    }

    @ParameterizedTest
    @DisplayName("rejects aliases that are malformed or could shadow a real route")
    @ValueSource(strings = {"ab", "aaaaaaaaaaaaaaaaa", "has space", "has/slash", "dot.dot",
            "emoji-\uD83D\uDE00", "links", "LINKS", "Health", "actuator"})
    void rejectsBadAliases(String alias) {
        assertThatThrownBy(() -> generator.validateAlias(alias))
                .isInstanceOf(InvalidAliasException.class);
    }

    @Test
    void rejectsBlankAlias() {
        assertThatThrownBy(() -> generator.validateAlias("   "))
                .isInstanceOf(InvalidAliasException.class);
    }

    @Test
    void trimsSurroundingWhitespace() {
        assertThat(generator.validateAlias("  my-link  ")).isEqualTo("my-link");
    }
}
