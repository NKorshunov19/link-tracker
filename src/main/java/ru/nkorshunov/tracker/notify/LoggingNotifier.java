package ru.nkorshunov.tracker.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Пишет уведомления в лог и держит их последние экземпляры в памяти.
 *
 * Список нужен интеграционным тестам, чтобы проверить, что уведомление действительно
 * ушло. Он ограничен по размеру: иначе на долгоживущем приложении это была бы
 * утечка памяти — список рос бы бесконечно.
 */
@Component
public class LoggingNotifier implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotifier.class);
    private static final int HISTORY_LIMIT = 100;

    private final List<Notification> recent = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void send(Notification notification) {
        log.info("Уведомление для {}: {}", notification.subscriber(), notification.text());
        synchronized (recent) {
            recent.add(notification);
            if (recent.size() > HISTORY_LIMIT) {
                recent.removeFirst();
            }
        }
    }

    public List<Notification> recent() {
        synchronized (recent) {
            return List.copyOf(recent);
        }
    }

    public void clear() {
        recent.clear();
    }
}
