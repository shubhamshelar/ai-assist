package com.shubham.aiassistant.vault;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeFileRepository extends JpaRepository<KnowledgeFile, UUID> {
    Optional<KnowledgeFile> findByFilePath(String filePath);
    List<KnowledgeFile> findByVaultPath(VaultPath vaultPath);
    long countByVaultPath(VaultPath vaultPath);
    void deleteByVaultPath(VaultPath vaultPath);
}
