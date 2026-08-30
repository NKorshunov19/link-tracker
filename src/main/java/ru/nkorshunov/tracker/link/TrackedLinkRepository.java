package ru.nkorshunov.tracker.link;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TrackedLinkRepository extends JpaRepository<TrackedLink, Long> {

    List<TrackedLink> findAllBySubscriber(String subscriber);

    Optional<TrackedLink> findBySubscriberAndOwnerAndRepository(String subscriber, String owner, String repository);

    /**
     * Ссылки, которые пора проверить: активные и не проверявшиеся дольше порога.
     *
     * Сортировка по времени последней проверки, новые (null) — первыми. Так очередь
     * честная: репозиторий, который давно не смотрели, обгоняет только что проверенный,
     * и ни одна ссылка не голодает.
     *
     * Pageable ограничивает пачку: за один тик планировщика мы не пытаемся обойти
     * все ссылки сразу, иначе при их росте один тик растянется на часы.
     */
    @Query("""
            select l from TrackedLink l
            where l.active = true
              and (l.lastCheckedAt is null or l.lastCheckedAt < :threshold)
            order by l.lastCheckedAt asc nulls first
            """)
    List<TrackedLink> findDueForCheck(@Param("threshold") Instant threshold, Pageable pageable);
}
