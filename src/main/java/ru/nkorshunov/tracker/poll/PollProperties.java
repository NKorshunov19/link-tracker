package ru.nkorshunov.tracker.poll;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.poll")
public record PollProperties(
        /** Насколько «протухшей» должна быть ссылка, чтобы её снова проверять. */
        Duration minInterval,
        /** Сколько ссылок обрабатывать за один тик планировщика. */
        @Positive int batchSize,
        /** После скольких подряд неудач ссылка отключается. */
        @Positive int failureLimit
) {
}
