package ru.nkorshunov.tracker.link;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TrackedLinkTest {

    private static final Instant EARLIER = Instant.parse("2026-08-01T10:00:00Z");
    private static final Instant LATER = Instant.parse("2026-08-02T10:00:00Z");
    private static final Instant NOW = Instant.parse("2026-08-30T12:00:00Z");

    private static TrackedLink link() {
        return new TrackedLink("nikita", new GitHubUrl("spring-projects", "spring-boot"));
    }

    @Test
    @DisplayName("первая проверка не уведомляет: подписчик только что добавил ссылку")
    void firstCheckDoesNotNotify() {
        TrackedLink link = link();

        boolean notify = link.applyUpdate(EARLIER, "etag-1", NOW);

        assertThat(notify).isFalse();
        assertThat(link.getLastPushedAt()).isEqualTo(EARLIER);
        assertThat(link.getEtag()).isEqualTo("etag-1");
    }

    @Test
    @DisplayName("более свежее изменение уведомляет")
    void newerPushNotifies() {
        TrackedLink link = link();
        link.applyUpdate(EARLIER, "etag-1", NOW);

        assertThat(link.applyUpdate(LATER, "etag-2", NOW)).isTrue();
    }

    @Test
    @DisplayName("то же самое время не уведомляет повторно")
    void samePushDoesNotNotifyAgain() {
        TrackedLink link = link();
        link.applyUpdate(LATER, "etag-1", NOW);

        assertThat(link.applyUpdate(LATER, "etag-1", NOW)).isFalse();
    }

    @Test
    @DisplayName("более старое время не уведомляет — защита от чудес на стороне API")
    void olderPushDoesNotNotify() {
        TrackedLink link = link();
        link.applyUpdate(LATER, "etag-1", NOW);

        assertThat(link.applyUpdate(EARLIER, "etag-2", NOW)).isFalse();
    }

    @Test
    @DisplayName("успешная проверка обнуляет счётчик неудач")
    void successResetsFailureCounter() {
        TrackedLink link = link();
        link.markFailure(NOW, 5);
        link.markFailure(NOW, 5);
        assertThat(link.getConsecutiveFailures()).isEqualTo(2);

        link.markUnchanged(NOW);

        assertThat(link.getConsecutiveFailures()).isZero();
        assertThat(link.isActive()).isTrue();
    }

    @Test
    @DisplayName("после лимита подряд идущих неудач ссылка отключается")
    void linkIsDeactivatedAfterFailureLimit() {
        TrackedLink link = link();

        for (int i = 0; i < 3; i++) {
            link.markFailure(NOW, 3);
        }

        assertThat(link.isActive()).isFalse();
    }
}
