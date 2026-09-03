package ru.nkorshunov.tracker.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
