package ru.nkorshunov.tracker.poll;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.poll")
public record PollProperties(
        Duration minInterval,
        @Positive int batchSize,
        @Positive int failureLimit
) {
}
