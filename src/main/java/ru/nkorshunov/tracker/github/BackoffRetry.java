package ru.nkorshunov.tracker.github;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class BackoffRetry {

    private static final Logger log = LoggerFactory.getLogger(BackoffRetry.class);
    private static final double JITTER_RATIO = 0.25;
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(1);

    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Sleeper sleeper;

    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    public BackoffRetry(int maxAttempts, Duration initialBackoff) {
        this(maxAttempts, initialBackoff, duration -> Thread.sleep(duration.toMillis()));
    }

    public BackoffRetry(int maxAttempts, Duration initialBackoff, Sleeper sleeper) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("Попыток должно быть хотя бы одна");
        }
        if (initialBackoff == null || initialBackoff.isNegative()) {
            throw new IllegalArgumentException("Задержка не может быть отрицательной");
        }
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.sleeper = sleeper;
    }

    public <T> T execute(Supplier<T> action, Predicate<T> shouldRetry) {
        T result = action.get();
        for (int attempt = 1; attempt < maxAttempts; attempt++) {
            if (!shouldRetry.test(result)) {
                return result;
            }
            Duration delay = delayForAttempt(attempt);
            log.debug("Попытка {} из {} неудачна, повтор через {} мс", attempt, maxAttempts, delay.toMillis());
            try {
                sleeper.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return result;
            }
            result = action.get();
        }
        return result;
    }

    Duration delayForAttempt(int attempt) {
        long base = baseDelayMillis(attempt);
        long jitter = (long) (base * JITTER_RATIO);
        long randomized = base + ThreadLocalRandom.current().nextLong(-jitter, jitter + 1);
        return Duration.ofMillis(Math.max(1, randomized));
    }

    // удвоение циклом, а не сдвигом: 1L << 63 ушёл бы в минус, а сдвиг на 64 считается по модулю разрядности
    private long baseDelayMillis(int attempt) {
        long capped = MAX_BACKOFF.toMillis();
        long millis = initialBackoff.toMillis();
        for (int i = 1; i < attempt && millis < capped; i++) {
            millis *= 2;
        }
        return Math.min(millis, capped);
    }
}
