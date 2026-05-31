package com.shubham.aiassistant.vault;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "vault_paths")
public class VaultPath {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 500)
    private String path;

    @Column(name = "added_at", nullable = false)
    private LocalDateTime addedAt;

    @Column(name = "last_scanned_at")
    private LocalDateTime lastScannedAt;

    /** Required by JPA. */
    public VaultPath() {}

    public VaultPath(UUID id, String path, LocalDateTime addedAt) {
        this.id = id;
        this.path = path;
        this.addedAt = addedAt;
    }

    public UUID getId()                            { return id; }
    public String getPath()                        { return path; }
    public LocalDateTime getAddedAt()              { return addedAt; }
    public LocalDateTime getLastScannedAt()        { return lastScannedAt; }

    public void setId(UUID id)                     { this.id = id; }
    public void setPath(String path)               { this.path = path; }
    public void setAddedAt(LocalDateTime t)        { this.addedAt = t; }
    public void setLastScannedAt(LocalDateTime t)  { this.lastScannedAt = t; }
}
