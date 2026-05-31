package com.shubham.aiassistant.vault;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownChunkerTest {

    // ── Basic chunking ────────────────────────────────────────────────────────

    @Test
    void nullAndBlank_returnEmpty() {
        assertThat(MarkdownChunker.chunk(null)).isEmpty();
        assertThat(MarkdownChunker.chunk("")).isEmpty();
        assertThat(MarkdownChunker.chunk("   ")).isEmpty();
    }

    @Test
    void shortText_returnsSingleChunk() {
        String text = "# Hello\nThis is a short note.";
        List<String> chunks = MarkdownChunker.chunk(text);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).contains("Hello");
    }

    @Test
    void largeText_producesMultipleChunks_allWithinSizeLimit() {
        String text = "A".repeat(3000);
        List<String> chunks = MarkdownChunker.chunk(text);
        assertThat(chunks.size()).isGreaterThan(1);
        for (String chunk : chunks) {
            assertThat(chunk.length()).isLessThanOrEqualTo(MarkdownChunker.CHUNK_SIZE);
        }
    }

    // ── Heading boundary respect ──────────────────────────────────────────────

    @Test
    void headingBoundary_neverSplitMidHeading() {
        // Build text with two distinct heading sections, each under CHUNK_SIZE
        StringBuilder sb = new StringBuilder();
        sb.append("# Section One\n");
        sb.append("Content of section one. ".repeat(10));
        sb.append("\n## Section Two\n");
        sb.append("Content of section two. ".repeat(10));

        List<String> chunks = MarkdownChunker.chunk(sb.toString());
        // Every chunk that contains "# Section" should start with it, not have it buried mid-chunk
        for (String chunk : chunks) {
            // If a chunk contains a heading, that heading should be at the start of its section
            // (i.e. no chunk starts with non-heading content followed by a heading mid-chunk)
            assertThat(chunk).doesNotMatch("(?s)\\S.*\\n# .*");
        }
    }

    @Test
    void multipleHeadings_chunkSetsStartWithHeadings() {
        String text = "# Alpha\n" + "x ".repeat(20)
                    + "\n# Beta\n" + "y ".repeat(20)
                    + "\n# Gamma\n" + "z ".repeat(20);

        List<String> chunks = MarkdownChunker.chunk(text);
        // At least one chunk should start with a heading
        long headingChunks = chunks.stream()
            .filter(c -> c.startsWith("#"))
            .count();
        assertThat(headingChunks).isGreaterThan(0);
    }

    // ── charChunk fallback ────────────────────────────────────────────────────

    @Test
    void charChunk_producesCorrectOverlap() {
        String text = "ABCDEFGHIJ"; // 10 chars
        List<String> chunks = MarkdownChunker.charChunk(text, 4, 1);
        // step = 4-1=3: "ABCD", "DEFG", "GHIJ"
        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0)).isEqualTo("ABCD");
        assertThat(chunks.get(1)).isEqualTo("DEFG");
        assertThat(chunks.get(2)).isEqualTo("GHIJ");
    }

    @Test
    void charChunk_singleChunkWhenTextFits() {
        List<String> chunks = MarkdownChunker.charChunk("Hello", 100, 10);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).isEqualTo("Hello");
    }
}
