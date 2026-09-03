package ru.nkorshunov.tracker.link;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "tracked_links",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_tracked_links_subscriber_repo",
                columnNames = {"subscriber", "owner_name", "repository_name"}))
public class TrackedLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String subscriber;

    @Column(name = "owner_name", nullable = false, length = 128)
    private String owner;

    @Column(name = "repository_name", nullable = false, length = 128)
    private String repository;

    @Column(name = "last_pushed_at")
    private Instant lastPushedAt;

    @Column(length = 128)
    private String etag;

    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected TrackedLink() {
    }

    public TrackedLink(String subscriber, GitHubUrl url) {
        this.subscriber = subscriber;
        this.owner = url.owner();
        this.repository = url.repository();
    }

    public boolean applyUpdate(Instant pushedAt, String newEtag, Instant checkedAt) {
        this.lastCheckedAt = checkedAt;
        this.etag = newEtag;
        this.consecutiveFailures = 0;

        boolean firstCheck = this.lastPushedAt == null;
        boolean isNewer = !firstCheck && pushedAt.isAfter(this.lastPushedAt);
        this.lastPushedAt = pushedAt;

        return isNewer;
    }

    public void markUnchanged(Instant checkedAt) {
        this.lastCheckedAt = checkedAt;
        this.consecutiveFailures = 0;
    }

    public void markThrottled(Instant checkedAt) {
        this.lastCheckedAt = checkedAt;
    }

    public void markFailure(Instant checkedAt, int failureLimit) {
        this.lastCheckedAt = checkedAt;
        this.consecutiveFailures++;
        if (this.consecutiveFailures >= failureLimit) {
            this.active = false;
        }
    }

    public void deactivate() {
        this.active = false;
    }

    public Long getId() {
        return id;
    }

    public String getSubscriber() {
        return subscriber;
    }

    public String getOwner() {
        return owner;
    }

    public String getRepository() {
        return repository;
    }

    public String fullName() {
        return owner + "/" + repository;
    }

    public Instant getLastPushedAt() {
        return lastPushedAt;
    }

    public String getEtag() {
        return etag;
    }

    public Instant getLastCheckedAt() {
        return lastCheckedAt;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public boolean isActive() {
        return active;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TrackedLink other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }
}
