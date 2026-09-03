package ru.nkorshunov.tracker.poll;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.nkorshunov.tracker.github.GitHubClient;
import ru.nkorshunov.tracker.github.PollResult;
import ru.nkorshunov.tracker.link.TrackedLink;
import ru.nkorshunov.tracker.link.TrackedLinkRepository;
import ru.nkorshunov.tracker.notify.Notification;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Обработка одной ссылки.
 *
 * Отдельный бин с REQUIRES_NEW: каждая ссылка обрабатывается в собственной транзакции.
 * Если бы весь тик планировщика шёл одной транзакцией, ошибка на девятой ссылке
 * откатила бы результаты первых восьми — и они проверялись бы заново на следующем тике.
 *
 * Уведомление здесь не отправляется, а только собирается: отправку делает вызывающий
 * после коммита. Иначе упавший Notifier откатывал бы уже записанное состояние ссылки,
 * и подписчик получал бы одно и то же обновление на каждом тике.
 */
@Component
public class LinkUpdateProcessor {

    private static final Logger log = LoggerFactory.getLogger(LinkUpdateProcessor.class);

    private final TrackedLinkRepository repository;
    private final GitHubClient gitHubClient;
    private final PollProperties properties;
    private final Clock clock;

    public LinkUpdateProcessor(TrackedLinkRepository repository,
                               GitHubClient gitHubClient,
                               PollProperties properties,
                               Clock clock) {
        this.repository = repository;
        this.gitHubClient = gitHubClient;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Notification> process(Long linkId) {
        TrackedLink link = repository.findById(linkId).orElse(null);
        if (link == null || !link.isActive()) {
            return Optional.empty();
        }

        PollResult result = gitHubClient.poll(link.getOwner(), link.getRepository(), link.getEtag());
        Instant now = clock.instant();

        // switch по sealed-интерфейсу: компилятор проверит, что разобраны все случаи.
        switch (result) {
            case PollResult.Updated updated -> {
                if (link.applyUpdate(updated.pushedAt(), updated.etag(), now)) {
                    return Optional.of(
                            new Notification(link.getSubscriber(), link.fullName(), updated.pushedAt()));
                }
            }
            case PollResult.NotModified ignored -> link.markUnchanged(now);
            case PollResult.NotFound ignored -> {
                log.info("Репозиторий {} больше не существует, ссылка отключена", link.fullName());
                link.deactivate();
            }
            case PollResult.RateLimited limited -> {
                log.warn("Лимит GitHub исчерпан, проверка {} отложена (Retry-After: {})",
                        link.fullName(), limited.retryAfter());
                link.markThrottled(now);
            }
            case PollResult.Failed failed -> {
                log.warn("Не удалось проверить {}: {}", link.fullName(), failed.reason());
                link.markFailure(now, properties.failureLimit());
            }
        }
        return Optional.empty();
    }
}
