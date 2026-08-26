package com.url.shortener.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class RedirectControllerTest {

    @ParameterizedTest
    @DisplayName("reduces a referer to its lowercase host and nothing else")
    @CsvSource({
            "https://News.Example.com/story?utm=abc, news.example.com",
            "http://blog.test:8080/a/b, blog.test",
            "https://example.com, example.com"})
    void extractsHost(String referer, String expected) {
        assertThat(RedirectController.referrerHost(referer)).isEqualTo(expected);
    }

    @Test
    @DisplayName("a query string carrying a token never reaches the analytics store")
    void discardsEverythingButTheHost() {
        String host = RedirectController.referrerHost(
                "https://mail.example.com/inbox?token=eyJhbGciOi&user=alice@example.com");
        assertThat(host).isEqualTo("mail.example.com");
    }

    @Test
    void treatsMissingOrMalformedRefererAsDirectTraffic() {
        assertThat(RedirectController.referrerHost(null)).isEmpty();
        assertThat(RedirectController.referrerHost("   ")).isEmpty();
        assertThat(RedirectController.referrerHost("not a url")).isEmpty();
        assertThat(RedirectController.referrerHost("/relative/path")).isEmpty();
    }
}
