package com.shubham.aiassistant.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "documents")
public class DocumentEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String filename;

    @Column(name = "sha256_hash", nullable = false, unique = true, length = 64)
    private String sha256Hash;

    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt;

    /** Required by JPA. */
    public DocumentEntity() {}

    public DocumentEntity(UUID id, String filename, String sha256Hash, LocalDateTime uploadedAt) {
        this.id = id;
        this.filename = filename;
        this.sha256Hash = sha256Hash;
        this.uploadedAt = uploadedAt;
    }

    public UUID getId()                       { return id; }
    public String getFilename()               { return filename; }
    public String getSha256Hash()             { return sha256Hash; }
    public LocalDateTime getUploadedAt()      { return uploadedAt; }

    public void setId(UUID id)                { this.id = id; }
    public void setFilename(String filename)  { this.filename = filename; }
    public void setSha256Hash(String h)       { this.sha256Hash = h; }
    public void setUploadedAt(LocalDateTime t){ this.uploadedAt = t; }
}
