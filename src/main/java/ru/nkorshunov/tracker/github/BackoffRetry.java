package ru.nkorshunov.tracker.github;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Повтор с экспоненциальной задержкой и джиттером.
 *
 * Зачем экспоненциальная задержка: если внешний сервис лёг, повторять каждые 100 мс —
 * значит добивать его и тратить свой лимит впустую. Задержка удваивается: 1с, 2с, 4с.
 *
 * Зачем джиттер: без него все клиенты, упавшие в одну секунду, повторят запрос
 * тоже в одну секунду и уронят сервис повторно — это называется thundering herd.
 * Случайный разброс ±25% размазывает нагрузку.
 *
 * В продакшене это делает Resilience4j. Здесь написано руками и покрыто тестами
 * намеренно: политика повторов — та часть, где ошибки дороги, и хотелось, чтобы
 * она была явной и проверяемой, а не спрятанной в конфиге.
 */
public class BackoffRetry {

    private static final Logger log = LoggerFactory.getLogger(BackoffRetry.class);
    private static final double JITTER_RATIO = 0.25;
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(1);

    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Sleeper sleeper;

    /** Отдельный интерфейс сна, чтобы тесты не ждали по-настоящему. */
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

    /**
     * Выполняет действие, повторяя его, пока результат считается «стоит повторить».
     * Возвращает последний полученный результат — даже если он неудачный:
     * решать, что с ним делать, должен вызывающий, а не политика повторов.
     */
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
                // Восстанавливаем флаг: проглотить прерывание — значит сломать остановку приложения.
                Thread.currentThread().interrupt();
                return result;
            }
            result = action.get();
        }
        return result;
    }

    /** Задержка для попытки: initial * 2^(attempt-1), сдвинутая случайно на ±25%. */
    Duration delayForAttempt(int attempt) {
        long base = baseDelayMillis(attempt);
        long jitter = (long) (base * JITTER_RATIO);
        long randomized = base + ThreadLocalRandom.current().nextLong(-jitter, jitter + 1);
        return Duration.ofMillis(Math.max(1, randomized));
    }

    // удвоение в цикле с проверкой потолка, а не сдвигом: 1L << 63 ушёл бы в минус,
    // а сдвиг на 64 и больше в Java вообще считается по модулю разрядности
    private long baseDelayMillis(int attempt) {
        long capped = MAX_BACKOFF.toMillis();
        long millis = initialBackoff.toMillis();
        for (int i = 1; i < attempt && millis < capped; i++) {
            millis *= 2;
        }
        return Math.min(millis, capped);
    }
}
