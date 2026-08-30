package ru.nkorshunov.tracker.github;

import java.time.Instant;

/**
 * Результат одного обращения к GitHub.
 *
 * Sealed-интерфейс из Java 21: компилятор знает полный список наследников, поэтому
 * switch по нему проверяется на полноту — забыть ветку не получится, сборка не пройдёт.
 * Это лучше, чем возвращать null или бросать исключение на каждый чих: три исхода
 * здесь равноправны и обрабатываются по-разному.
 */
public sealed interface PollResult {

    /** В репозитории что-то поменялось. etag сохраняем, чтобы в следующий раз спросить условно. */
    record Updated(Instant pushedAt, String etag) implements PollResult {
    }

    /** GitHub ответил 304: с прошлого раза ничего не изменилось. Это не ошибка. */
    record NotModified() implements PollResult {
    }

    /**
     * Обращение не удалось.
     * retryable отделяет временные сбои (5xx, таймаут, превышен лимит) от постоянных
     * (404 — репозиторий удалён): повторять второе бессмысленно, это только съест лимит.
     */
    record Failed(String reason, boolean retryable) implements PollResult {
    }
}
