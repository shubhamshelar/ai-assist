package com.shubham.aiassistant.vault;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "knowledge_files")
public class KnowledgeFile {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vault_path_id", nullable = false)
    private VaultPath vaultPath;

    @Column(name = "file_path", nullable = false, unique = true, length = 1000)
    private String filePath;

    @Column(name = "sha256_hash", nullable = false, length = 64)
    private String sha256Hash;

    @Column(name = "last_modified", nullable = false)
    private LocalDateTime lastModified;

    @Column(name = "indexed_at", nullable = false)
    private LocalDateTime indexedAt;

    /** Required by JPA. */
    public KnowledgeFile() {}

    public KnowledgeFile(UUID id, VaultPath vaultPath, String filePath,
                         String sha256Hash, LocalDateTime lastModified, LocalDateTime indexedAt) {
        this.id = id;
        this.vaultPath = vaultPath;
        this.filePath = filePath;
        this.sha256Hash = sha256Hash;
        this.lastModified = lastModified;
        this.indexedAt = indexedAt;
    }

    public UUID getId()                     { return id; }
    public VaultPath getVaultPath()         { return vaultPath; }
    public String getFilePath()             { return filePath; }
    public String getSha256Hash()           { return sha256Hash; }
    public LocalDateTime getLastModified()  { return lastModified; }
    public LocalDateTime getIndexedAt()     { return indexedAt; }

    public void setId(UUID id)                     { this.id = id; }
    public void setVaultPath(VaultPath vp)         { this.vaultPath = vp; }
    public void setFilePath(String fp)             { this.filePath = fp; }
    public void setSha256Hash(String h)            { this.sha256Hash = h; }
    public void setLastModified(LocalDateTime t)   { this.lastModified = t; }
    public void setIndexedAt(LocalDateTime t)      { this.indexedAt = t; }
}
