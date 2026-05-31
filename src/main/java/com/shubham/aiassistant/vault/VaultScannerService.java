package com.shubham.aiassistant.vault;

import com.shubham.aiassistant.util.HashUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scans configured vault paths for Markdown files and keeps the vector store
 * in sync (new / modified / deleted detection via SHA-256 + DB tracking).
 *
 * <p>Runs automatically on startup via {@link ApplicationReadyEvent} and can be
 * triggered on demand via {@link #scanAll()}.
 */
@Service
public class VaultScannerService {

    private static final Logger log = LoggerFactory.getLogger(VaultScannerService.class);

    /**
     * Raw JDBC delete used to remove vectors by filePath metadata, since Spring AI
     * PgVectorStore does not expose a deleteByMetadata API.
     */
    private static final String DELETE_BY_FILE_PATH_SQL =
        "DELETE FROM vector_store WHERE metadata->>'filePath' = ?";

    private final VaultPathRepository     vaultPathRepository;
    private final KnowledgeFileRepository knowledgeFileRepository;
    private final VectorStore             vectorStore;
    private final JdbcTemplate            jdbcTemplate;

    public VaultScannerService(VaultPathRepository vaultPathRepository,
                               KnowledgeFileRepository knowledgeFileRepository,
                               VectorStore vectorStore,
                               JdbcTemplate jdbcTemplate) {
        this.vaultPathRepository     = vaultPathRepository;
        this.knowledgeFileRepository = knowledgeFileRepository;
        this.vectorStore             = vectorStore;
        this.jdbcTemplate            = jdbcTemplate;
    }

    // ── Startup scan ──────────────────────────────────────────────────────────

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        log.info("ApplicationReadyEvent: starting vault scan");
        scanAll();
    }

    // ── Manual / async scan ───────────────────────────────────────────────────

    @Async
    public void scanAll() {
        List<VaultPath> paths = vaultPathRepository.findAll();
        if (paths.isEmpty()) {
            log.info("No vault paths configured — skipping scan");
            return;
        }
        log.info("Scanning {} vault path(s)", paths.size());
        for (VaultPath vp : paths) {
            try {
                scanVaultPath(vp);
            } catch (Exception e) {
                log.error("Error scanning vault path '{}': {}", vp.getPath(), e.getMessage(), e);
            }
        }
    }

    /**
     * Scans a single vault path: detects new, modified, and deleted `.md` files,
     * updates the vector store and tracking table accordingly, then updates
     * {@code lastScannedAt}.
     */
    @Transactional
    public void scanVaultPath(VaultPath vaultPath) {
        Path root = Path.of(vaultPath.getPath());
        if (!Files.isDirectory(root)) {
            log.warn("Vault path '{}' is not a directory — skipping", root);
            return;
        }

        log.info("Scanning vault: '{}'", root);
        int newCount = 0, updatedCount = 0, deletedCount = 0, unchangedCount = 0;

        // ── Collect all .md files on disk ─────────────────────────────────────
        Set<String> diskFilePaths = new HashSet<>();
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> mdFiles = walk
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".md"))
                .toList();

            for (Path file : mdFiles) {
                String filePath = file.toAbsolutePath().toString();
                diskFilePaths.add(filePath);

                byte[] bytes;
                try {
                    bytes = Files.readAllBytes(file);
                } catch (IOException e) {
                    log.warn("Cannot read '{}': {}", filePath, e.getMessage());
                    continue;
                }

                String sha256 = HashUtils.sha256Hex(bytes);
                LocalDateTime lastModified = LocalDateTime.ofInstant(
                    Files.getLastModifiedTime(file).toInstant(), ZoneId.systemDefault());

                Optional<KnowledgeFile> existing = knowledgeFileRepository.findByFilePath(filePath);

                if (existing.isEmpty()) {
                    // ── New file ──────────────────────────────────────────────
                    indexFile(vaultPath, filePath, bytes, sha256, lastModified, null);
                    newCount++;
                } else if (!existing.get().getSha256Hash().equals(sha256)) {
                    // ── Modified file ─────────────────────────────────────────
                    deleteVectors(filePath);
                    indexFile(vaultPath, filePath, bytes, sha256, lastModified, existing.get());
                    updatedCount++;
                } else {
                    // ── Unchanged ─────────────────────────────────────────────
                    unchangedCount++;
                }
            }
        } catch (IOException e) {
            log.error("Failed to walk vault path '{}': {}", root, e.getMessage(), e);
            return;
        }

        // ── Detect deleted files (in DB but no longer on disk) ────────────────
        List<KnowledgeFile> tracked = knowledgeFileRepository.findByVaultPath(vaultPath);
        for (KnowledgeFile kf : tracked) {
            if (!diskFilePaths.contains(kf.getFilePath())) {
                deleteVectors(kf.getFilePath());
                knowledgeFileRepository.delete(kf);
                deletedCount++;
            }
        }

        // ── Update lastScannedAt ──────────────────────────────────────────────
        vaultPath.setLastScannedAt(LocalDateTime.now());
        vaultPathRepository.save(vaultPath);

        log.info("Vault scan '{}': {} new, {} updated, {} deleted, {} unchanged",
                 root, newCount, updatedCount, deletedCount, unchangedCount);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Chunks and embeds a markdown file, storing chunks in the vector store and
     * inserting or updating the corresponding {@link KnowledgeFile} tracking row.
     */
    private void indexFile(VaultPath vaultPath, String filePath, byte[] bytes,
                           String sha256, LocalDateTime lastModified,
                           KnowledgeFile existingRow) {
        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        List<String> chunks = MarkdownChunker.chunk(text);

        List<Document> docs = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            docs.add(new Document(
                chunks.get(i),
                Map.of(
                    "source",      "vault",
                    "filePath",    filePath,
                    "vaultPathId", vaultPath.getId().toString(),
                    "chunkIndex",  i
                )
            ));
        }

        if (!docs.isEmpty()) {
            vectorStore.add(docs);
            log.debug("Indexed {} chunks for '{}'", docs.size(), filePath);
        }

        LocalDateTime now = LocalDateTime.now();
        if (existingRow == null) {
            knowledgeFileRepository.save(
                new KnowledgeFile(UUID.randomUUID(), vaultPath, filePath, sha256, lastModified, now));
        } else {
            existingRow.setSha256Hash(sha256);
            existingRow.setLastModified(lastModified);
            existingRow.setIndexedAt(now);
            knowledgeFileRepository.save(existingRow);
        }
    }

    /**
     * Deletes all vector store entries whose {@code filePath} metadata matches.
     * Uses raw JDBC since Spring AI PgVectorStore doesn't expose metadata-based delete.
     */
    public void deleteVectors(String filePath) {
        int deleted = jdbcTemplate.update(DELETE_BY_FILE_PATH_SQL, filePath);
        log.debug("Deleted {} vectors for filePath='{}'", deleted, filePath);
    }

    /**
     * Deletes all vectors and tracking rows for a given vault path.
     * Called when a vault path is removed via the API.
     */
    @Transactional
    public void deleteVaultPathData(VaultPath vaultPath) {
        List<KnowledgeFile> files = knowledgeFileRepository.findByVaultPath(vaultPath);
        for (KnowledgeFile kf : files) {
            deleteVectors(kf.getFilePath());
        }
        knowledgeFileRepository.deleteByVaultPath(vaultPath);
        log.info("Deleted all vectors and tracking rows for vault path '{}'", vaultPath.getPath());
    }
}
