package ru.nkorshunov.tracker.notify;

/**
 * Куда уходит уведомление.
 *
 * Отдельный интерфейс, а не прямой вызов Telegram, потому что способ доставки —
 * это деталь. Сейчас реализация одна, логирующая; добавить телеграм-бота или
 * отправку в Kafka означает написать второй класс, не трогая логику проверки.
 */
public interface Notifier {

    void send(Notification notification);
}
