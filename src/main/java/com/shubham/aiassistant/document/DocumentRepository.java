package com.shubham.aiassistant.document;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<DocumentEntity, UUID> {
    Optional<DocumentEntity> findBySha256Hash(String sha256Hash);
}
