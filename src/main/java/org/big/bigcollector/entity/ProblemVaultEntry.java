package org.big.bigcollector.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "problem_vault")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class ProblemVaultEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "problem_type", length = 50)
    private String problemType;

    @Column(length = 100)
    private String industry;

    @Column(name = "target_customer", length = 200)
    private String targetCustomer;

    // DPGTF scores
    @Column(name = "score_demand", precision = 4, scale = 2)
    private BigDecimal scoreDemand;

    @Column(name = "score_pain", precision = 4, scale = 2)
    private BigDecimal scorePain;

    @Column(name = "score_growth", precision = 4, scale = 2)
    private BigDecimal scoreGrowth;

    @Column(name = "score_tractability", precision = 4, scale = 2)
    private BigDecimal scoreTractability;

    @Column(name = "score_frequency", precision = 4, scale = 2)
    private BigDecimal scoreFrequency;

    @Column(name = "overall_score", precision = 5, scale = 2)
    private BigDecimal overallScore;

    @Column(precision = 4, scale = 2)
    private BigDecimal confidence;

    @Column(name = "source_count")
    @Builder.Default
    private Integer sourceCount = 1;

    @Column(name = "embedding", columnDefinition = "vector(1536)")
    private float[] embedding;

    @Column(name = "is_public", nullable = false)
    @Builder.Default
    private Boolean isPublic = false;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "problemVaultEntry", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ProblemEvidence> evidence = new ArrayList<>();

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.firstSeenAt == null) {
            this.firstSeenAt = now;
        }
        if (this.lastSeenAt == null) {
            this.lastSeenAt = now;
        }
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now();
    }
}
