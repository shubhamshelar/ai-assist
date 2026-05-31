package com.shubham.aiassistant.vault;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VaultPathRepository extends JpaRepository<VaultPath, UUID> {
    Optional<VaultPath> findByPath(String path);
}
