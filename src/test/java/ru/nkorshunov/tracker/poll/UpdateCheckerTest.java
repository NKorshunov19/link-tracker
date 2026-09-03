package ru.nkorshunov.tracker.poll;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.nkorshunov.tracker.link.TrackedLinkRepository;
import ru.nkorshunov.tracker.notify.Notification;
import ru.nkorshunov.tracker.notify.Notifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UpdateCheckerTest {

    private final TrackedLinkRepository repository = mock(TrackedLinkRepository.class);
    private final LinkUpdateProcessor processor = mock(LinkUpdateProcessor.class);
    private final Notifier notifier = mock(Notifier.class);

    private final UpdateChecker checker = new UpdateChecker(
            repository, processor, notifier,
            new PollProperties(Duration.ZERO, 50, 3),
            Clock.systemUTC());

    private void due(Long... ids) {
        when(repository.findIdsDueForCheck(any(), any())).thenReturn(List.of(ids));
    }

    @Test
    @DisplayName("исключение на одной ссылке не останавливает обход остальных")
    void oneBrokenLinkDoesNotStopTheTick() {
        due(1L, 2L, 3L);
        when(processor.process(1L)).thenReturn(Optional.empty());
        when(processor.process(2L)).thenThrow(new IllegalStateException("сломалось"));
        when(processor.process(3L)).thenReturn(Optional.empty());

        int processed = checker.runOnce();

        assertThat(processed).isEqualTo(2);
        verify(processor).process(3L);
    }

    @Test
    @DisplayName("упавшая доставка уведомления не срывает остальные ссылки")
    void notifierFailureDoesNotStopTheTick() {
        due(1L, 2L);
        when(processor.process(any())).thenReturn(Optional.of(
                new Notification("nikita", "owner/repo", Instant.parse("2026-08-30T10:00:00Z"))));
        doThrow(new IllegalStateException("телеграм недоступен")).when(notifier).send(any());

        int processed = checker.runOnce();

        assertThat(processed).as("состояние ссылок уже закоммичено, тик считается успешным").isEqualTo(2);
        verify(notifier, times(2)).send(any());
    }

    @Test
    @DisplayName("уведомление уходит ровно одно на одну ссылку")
    void sendsExactlyOneNotificationPerLink() {
        due(1L);
        Notification notification =
                new Notification("nikita", "owner/repo", Instant.parse("2026-08-30T10:00:00Z"));
        when(processor.process(1L)).thenReturn(Optional.of(notification));

        checker.runOnce();

        verify(notifier, times(1)).send(notification);
    }
}
