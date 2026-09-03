package ru.nkorshunov.tracker.github;

import java.time.Duration;
import java.time.Instant;

/**
 * Результат одного обращения к GitHub.
 *
 * Sealed-интерфейс из Java 21: компилятор знает полный список наследников, поэтому
 * switch по нему проверяется на полноту — забыть ветку не получится, сборка не пройдёт.
 * Это лучше, чем возвращать null или бросать исключение на каждый чих: исходы
 * здесь равноправны и обрабатываются по-разному.
 */
public sealed interface PollResult {

    /** В репозитории что-то поменялось. etag сохраняем, чтобы в следующий раз спросить условно. */
    record Updated(Instant pushedAt, String etag) implements PollResult {
    }

    /** GitHub ответил 304: с прошлого раза ничего не изменилось. Это не ошибка. */
    record NotModified() implements PollResult {
    }

    /** Репозитория больше нет: удалён или переименован. Единственный случай, когда ссылку выключают сразу. */
    record NotFound() implements PollResult {
    }

    /**
     * Лимит запросов GitHub исчерпан. Отдельный случай, а не Failed: сама ссылка исправна,
     * и считать это её неудачей нельзя — иначе за несколько тиков без токена
     * (60 запросов в час на IP) выключились бы все подписки разом.
     * retryAfter — из заголовка Retry-After, если он пришёл.
     */
    record RateLimited(Duration retryAfter) implements PollResult {
    }

    /**
     * Обращение не удалось.
     * retryable отделяет временные сбои (5xx, таймаут) от постоянных (битый ответ, нет доступа):
     * повторять второе в том же тике бессмысленно.
     */
    record Failed(String reason, boolean retryable) implements PollResult {
    }
}
