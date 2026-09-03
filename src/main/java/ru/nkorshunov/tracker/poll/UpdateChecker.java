package ru.nkorshunov.tracker.poll;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.nkorshunov.tracker.link.TrackedLinkRepository;
import ru.nkorshunov.tracker.notify.Notification;
import ru.nkorshunov.tracker.notify.Notifier;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Планировщик: раз в интервал берёт пачку «протухших» ссылок и отдаёт их на обработку.
 *
 * fixedDelay, а не fixedRate — это важно. fixedRate отсчитывает от НАЧАЛА предыдущего
 * запуска: если обход занял дольше интервала, следующий стартует немедленно, и запуски
 * начинают накладываться. fixedDelay отсчитывает от ОКОНЧАНИЯ, поэтому перегрузки
 * внешнего API не случится, даже если GitHub отвечает медленно.
 *
 * Сам обход не транзакционный: транзакцию открывает LinkUpdateProcessor на каждую
 * ссылку отдельно. Держать одну транзакцию открытой на всё время сетевых запросов —
 * верный способ исчерпать пул соединений к базе.
 */
@Component
public class UpdateChecker {

    private static final Logger log = LoggerFactory.getLogger(UpdateChecker.class);

    private final TrackedLinkRepository repository;
    private final LinkUpdateProcessor processor;
    private final Notifier notifier;
    private final PollProperties properties;
    private final Clock clock;

    public UpdateChecker(TrackedLinkRepository repository,
                         LinkUpdateProcessor processor,
                         Notifier notifier,
                         PollProperties properties,
                         Clock clock) {
        this.repository = repository;
        this.processor = processor;
        this.notifier = notifier;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.poll.tick:PT30S}")
    public void scheduledCheck() {
        try {
            int processed = runOnce();
            if (processed > 0) {
                log.debug("Проверено ссылок за тик: {}", processed);
            }
        } catch (RuntimeException e) {
            // недоступная база не должна ронять тик молча — иначе обход просто перестанет работать
            log.error("Тик планировщика завершился ошибкой", e);
        }
    }

    /**
     * Один проход по очереди. Возвращает число обработанных ссылок.
     * Вынесено отдельным публичным методом, чтобы тесты запускали обход вручную,
     * не дожидаясь срабатывания планировщика.
     */
    public int runOnce() {
        Instant threshold = clock.instant().minus(properties.minInterval());
        List<Long> due = repository.findIdsDueForCheck(
                threshold, PageRequest.of(0, properties.batchSize()));

        int processed = 0;
        for (Long linkId : due) {
            try {
                processor.process(linkId).ifPresent(this::deliver);
                processed++;
            } catch (RuntimeException e) {
                // Одна упавшая ссылка не должна останавливать обход остальных.
                log.error("Ошибка обработки ссылки {}", linkId, e);
            }
        }
        return processed;
    }

    /** Доставка идёт после коммита и вне транзакции, поэтому её сбой ничего не откатывает. */
    private void deliver(Notification notification) {
        try {
            notifier.send(notification);
        } catch (RuntimeException e) {
            log.error("Не удалось доставить уведомление подписчику {}", notification.subscriber(), e);
        }
    }
}
