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
        String token,
        @Positive int maxAttempts,
        Duration initialBackoff,
        Duration connectTimeout,
        Duration readTimeout
) {
    @Override
    public String toString() {
        return "GitHubProperties[baseUrl=%s, token=%s, maxAttempts=%d, initialBackoff=%s, connectTimeout=%s, readTimeout=%s]"
                .formatted(baseUrl, token == null || token.isBlank() ? "нет" : "***",
                        maxAttempts, initialBackoff, connectTimeout, readTimeout);
    }
}
