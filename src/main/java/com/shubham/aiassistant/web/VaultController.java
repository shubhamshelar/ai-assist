package com.shubham.aiassistant.web;

import com.shubham.aiassistant.vault.KnowledgeFileRepository;
import com.shubham.aiassistant.vault.VaultPath;
import com.shubham.aiassistant.vault.VaultPathRepository;
import com.shubham.aiassistant.vault.VaultScannerService;
import com.shubham.aiassistant.web.dto.VaultPathRequest;
import com.shubham.aiassistant.web.dto.VaultPathResponse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/vault")
public class VaultController {

    private static final Logger log = LoggerFactory.getLogger(VaultController.class);

    private final VaultPathRepository     vaultPathRepository;
    private final KnowledgeFileRepository knowledgeFileRepository;
    private final VaultScannerService     vaultScannerService;

    public VaultController(VaultPathRepository vaultPathRepository,
                           KnowledgeFileRepository knowledgeFileRepository,
                           VaultScannerService vaultScannerService) {
        this.vaultPathRepository     = vaultPathRepository;
        this.knowledgeFileRepository = knowledgeFileRepository;
        this.vaultScannerService     = vaultScannerService;
    }

    /** GET /vault/paths — list all configured vault paths with file count. */
    @GetMapping("/paths")
    public List<VaultPathResponse> listPaths() {
        return vaultPathRepository.findAll().stream()
            .map(this::toResponse)
            .toList();
    }

    /** POST /vault/paths — add a new vault path. */
    @PostMapping("/paths")
    public ResponseEntity<VaultPathResponse> addPath(@RequestBody VaultPathRequest req) {
        String pathStr = req.path();
        if (pathStr == null || pathStr.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Path must not be blank");
        }
        if (!Files.isDirectory(Path.of(pathStr))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Path does not exist or is not a directory: " + pathStr);
        }
        if (vaultPathRepository.findByPath(pathStr).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Path already configured: " + pathStr);
        }

        VaultPath vp = new VaultPath(UUID.randomUUID(), pathStr, LocalDateTime.now());
        vaultPathRepository.save(vp);
        log.info("Vault path added: '{}'", pathStr);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(vp));
    }

    /** DELETE /vault/paths/{id} — remove a vault path and all its indexed vectors. */
    @DeleteMapping("/paths/{id}")
    public ResponseEntity<Void> removePath(@PathVariable UUID id) {
        VaultPath vp = vaultPathRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Vault path not found: " + id));

        vaultScannerService.deleteVaultPathData(vp);
        vaultPathRepository.delete(vp);
        log.info("Vault path removed: '{}'", vp.getPath());
        return ResponseEntity.noContent().build();
    }

    /** POST /vault/scan — trigger a full async scan of all vault paths. */
    @PostMapping("/scan")
    public ResponseEntity<Void> triggerScan() {
        log.info("Manual vault scan triggered via API");
        vaultScannerService.scanAll();
        return ResponseEntity.accepted().build();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private VaultPathResponse toResponse(VaultPath vp) {
        long fileCount = knowledgeFileRepository.countByVaultPath(vp);
        return new VaultPathResponse(
            vp.getId(), vp.getPath(), vp.getAddedAt(), vp.getLastScannedAt(), fileCount);
    }
}
