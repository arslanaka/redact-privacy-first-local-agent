package dev.privacyagent.audit;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    List<AuditEvent> findByDocumentIdOrderByCreatedAtAscIdAsc(UUID documentId);

    @Modifying
    @Query("delete from AuditEvent a where a.documentId = :documentId")
    int deleteByDocumentId(UUID documentId);
}
