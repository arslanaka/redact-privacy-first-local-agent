package dev.privacyagent.document;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    @Query("select d.id from Document d where d.createdAt < :cutoff")
    List<UUID> findIdsCreatedBefore(Instant cutoff);
}
