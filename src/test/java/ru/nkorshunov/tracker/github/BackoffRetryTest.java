package ru.nkorshunov.tracker.github;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class BackoffRetryTest {

    private final List<Duration> slept = new ArrayList<>();

    private BackoffRetry retry(int attempts) {
        return new BackoffRetry(attempts, Duration.ofSeconds(1), slept::add);
    }

    @Test
    @DisplayName("успех с первой попытки — повторов нет")
    void doesNotRetryOnSuccess() {
        AtomicInteger calls = new AtomicInteger();

        String result = retry(3).execute(() -> {
            calls.incrementAndGet();
            return "ok";
        }, value -> false);

        assertThat(result).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(1);
        assertThat(slept).isEmpty();
    }

    @Test
    @DisplayName("повторяет до успеха")
    void retriesUntilSuccess() {
        AtomicInteger calls = new AtomicInteger();

        String result = retry(5).execute(
                () -> calls.incrementAndGet() < 3 ? "fail" : "ok",
                "fail"::equals);

        assertThat(result).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(3);
        assertThat(slept).hasSize(2);
    }

    @Test
    @DisplayName("исчерпав попытки, возвращает последний результат, а не бросает")
    void returnsLastFailureWhenAttemptsExhausted() {
        AtomicInteger calls = new AtomicInteger();

        String result = retry(3).execute(() -> {
            calls.incrementAndGet();
            return "fail";
        }, "fail"::equals);

        assertThat(result).isEqualTo("fail");
        assertThat(calls.get()).as("ровно maxAttempts вызовов").isEqualTo(3);
        assertThat(slept).hasSize(2);
    }

    @Test
    @DisplayName("задержка растёт экспоненциально")
    void delayGrowsExponentially() {
        BackoffRetry backoff = retry(4);

        assertThat(backoff.delayForAttempt(1).toMillis()).isBetween(750L, 1250L);
        assertThat(backoff.delayForAttempt(2).toMillis()).isBetween(1500L, 2500L);
        assertThat(backoff.delayForAttempt(3).toMillis()).isBetween(3000L, 5000L);
    }

    @Test
    @DisplayName("одна попытка — без повторов")
    void singleAttemptMeansNoRetries() {
        AtomicInteger calls = new AtomicInteger();

        retry(1).execute(() -> {
            calls.incrementAndGet();
            return "fail";
        }, "fail"::equals);

        assertThat(calls.get()).isEqualTo(1);
        assertThat(slept).isEmpty();
    }

    @Test
    @DisplayName("задержка упирается в потолок и не переполняется")
    void delayIsCappedAndDoesNotOverflow() {
        BackoffRetry backoff = new BackoffRetry(64, Duration.ofSeconds(1), slept::add);

        assertThat(backoff.delayForAttempt(40).toMillis())
                .as("не ушло в минус")
                .isBetween(45_000L, 75_000L);
        assertThat(backoff.delayForAttempt(63).toMillis()).isBetween(45_000L, 75_000L);
    }

    @Test
    @DisplayName("нулевая задержка не роняет джиттер")
    void zeroBackoffStillProducesPositiveDelay() {
        BackoffRetry backoff = new BackoffRetry(3, Duration.ZERO, slept::add);

        assertThat(backoff.delayForAttempt(1).toMillis()).isPositive();
    }

    @Test
    @DisplayName("отрицательная задержка и ноль попыток отвергаются")
    void invalidSettingsAreRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new BackoffRetry(0, Duration.ofSeconds(1)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new BackoffRetry(3, Duration.ofSeconds(-1)));
    }

    @Test
    @DisplayName("прерывание потока прекращает повторы и восстанавливает флаг")
    void interruptStopsRetriesAndRestoresFlag() {
        BackoffRetry interrupting = new BackoffRetry(5, Duration.ofSeconds(1), duration -> {
            throw new InterruptedException("прервано");
        });
        AtomicInteger calls = new AtomicInteger();

        String result = interrupting.execute(() -> {
            calls.incrementAndGet();
            return "fail";
        }, "fail"::equals);

        assertThat(result).isEqualTo("fail");
        assertThat(calls.get()).as("после прерывания повтора не было").isEqualTo(1);
        assertThat(Thread.interrupted()).as("флаг прерывания восстановлен").isTrue();
    }
}
