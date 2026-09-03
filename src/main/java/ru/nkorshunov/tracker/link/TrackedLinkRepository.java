package ru.nkorshunov.tracker.link;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface TrackedLinkRepository extends JpaRepository<TrackedLink, Long> {

    List<TrackedLink> findAllBySubscriber(String subscriber);

    boolean existsBySubscriberAndOwnerAndRepository(String subscriber, String owner, String repository);

    // только id: сущность всё равно перечитывается заново в транзакции обработчика
    @Query("""
            select l.id from TrackedLink l
            where l.active = true
              and (l.lastCheckedAt is null or l.lastCheckedAt < :threshold)
            order by l.lastCheckedAt asc nulls first
            """)
    List<Long> findIdsDueForCheck(@Param("threshold") Instant threshold, Pageable pageable);
}
