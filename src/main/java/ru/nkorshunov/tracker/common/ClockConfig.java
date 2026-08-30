package ru.nkorshunov.tracker.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    /**
     * Часы отдельным бином, а не Instant.now() по коду.
     * Так в тестах можно подменить время на фиксированное и проверять логику
     * «прошёл ли интервал», не выжидая его по-настоящему.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
