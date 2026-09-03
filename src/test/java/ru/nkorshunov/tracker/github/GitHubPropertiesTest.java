package ru.nkorshunov.tracker.github;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubPropertiesTest {

    @Test
    @DisplayName("токен не попадает в строковое представление конфигурации")
    void toStringHidesToken() {
        GitHubProperties properties = new GitHubProperties(
                "https://api.github.com", "ghp_секретный_токен", 3,
                Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(5));

        assertThat(properties.toString())
                .doesNotContain("ghp_секретный_токен")
                .contains("https://api.github.com");
    }

    @Test
    @DisplayName("пустой токен так и виден как отсутствующий")
    void toStringMarksMissingToken() {
        GitHubProperties properties = new GitHubProperties(
                "https://api.github.com", "", 3,
                Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(5));

        assertThat(properties.toString()).contains("token=нет");
    }
}
