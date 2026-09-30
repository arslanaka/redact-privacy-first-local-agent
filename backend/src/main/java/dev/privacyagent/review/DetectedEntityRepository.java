package dev.privacyagent.review;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface DetectedEntityRepository extends JpaRepository<DetectedEntity, UUID> {

    List<DetectedEntity> findByDocumentIdOrderByStartOffsetAsc(UUID documentId);

    List<DetectedEntity> findByDocumentIdAndReviewStatusOrderByStartOffsetAsc(UUID documentId, ReviewStatus status);

    long countByDocumentIdAndReviewStatus(UUID documentId, ReviewStatus status);

    long countByDocumentId(UUID documentId);

    @Modifying
    @Query("delete from DetectedEntity e where e.documentId = :documentId")
    int deleteByDocumentId(UUID documentId);
}
