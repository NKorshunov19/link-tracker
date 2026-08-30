package ru.nkorshunov.tracker.github;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.github")
public record GitHubProperties(
        @NotBlank String baseUrl,
        /** Токен необязателен, но без него лимит GitHub — 60 запросов в час на IP вместо 5000. */
        String token,
        @Positive int maxAttempts,
        Duration initialBackoff,
        Duration connectTimeout,
        Duration readTimeout
) {
}
