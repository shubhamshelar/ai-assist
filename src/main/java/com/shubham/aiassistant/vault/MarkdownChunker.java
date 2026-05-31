package com.shubham.aiassistant.vault;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Chunks markdown text into overlapping segments, respecting heading boundaries
 * so headings are never split mid-line.
 *
 * <p>Strategy:
 * <ol>
 *   <li>Split the raw text into sections on any ATX heading line ({@code ^#+ }).
 *   <li>Accumulate sections into a running buffer; when the buffer exceeds
 *       {@link #CHUNK_SIZE} chars, flush it as a chunk (with overlap carry-forward).
 *   <li>Any section that is itself larger than {@link #CHUNK_SIZE} is further
 *       split character-by-character using the overlap step — headings will
 *       still appear at the start of each sub-chunk.
 * </ol>
 */
public class MarkdownChunker {

    public static final int CHUNK_SIZE    = 2000;
    public static final int CHUNK_OVERLAP = 250;

    /** ATX heading pattern: line starting with one or more # followed by a space. */
    private static final Pattern HEADING_SPLIT = Pattern.compile("(?m)(?=^#+\\s)", Pattern.MULTILINE);

    private MarkdownChunker() {}

    /**
     * Splits {@code markdownText} into chunks of at most {@link #CHUNK_SIZE}
     * characters, carrying the last {@link #CHUNK_OVERLAP} characters forward
     * into the next chunk. Heading lines are never split.
     *
     * @param markdownText raw markdown string
     * @return ordered list of non-empty chunk strings
     */
    public static List<String> chunk(String markdownText) {
        if (markdownText == null || markdownText.isBlank()) return List.of();

        // Split on heading boundaries — sections start at a heading or the very beginning
        String[] sections = HEADING_SPLIT.split(markdownText);

        List<String> result = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();

        for (String section : sections) {
            if (section.isBlank()) continue;

            // If adding this section would exceed the chunk size, flush first
            if (buffer.length() > 0 && buffer.length() + section.length() > CHUNK_SIZE) {
                result.add(buffer.toString().strip());
                // carry forward the overlap tail of the current buffer
                String tail = buffer.substring(Math.max(0, buffer.length() - CHUNK_OVERLAP));
                buffer.setLength(0);
                buffer.append(tail);
            }

            // If the section alone is larger than CHUNK_SIZE, sub-chunk it
            if (section.length() > CHUNK_SIZE) {
                // flush whatever is in buffer first
                if (buffer.length() > 0) {
                    result.add(buffer.toString().strip());
                    buffer.setLength(0);
                }
                result.addAll(charChunk(section, CHUNK_SIZE, CHUNK_OVERLAP));
            } else {
                buffer.append(section);
            }
        }

        // flush remaining buffer
        if (!buffer.isEmpty() && !buffer.toString().isBlank()) {
            result.add(buffer.toString().strip());
        }

        // Remove duplicates that can appear from overlap logic
        result.removeIf(String::isBlank);
        return result;
    }

    /**
     * Simple character-based chunker used as a fallback for oversized sections.
     */
    static List<String> charChunk(String text, int size, int overlap) {
        List<String> result = new ArrayList<>();
        int step = Math.max(1, size - overlap);
        for (int i = 0; i < text.length(); i += step) {
            result.add(text.substring(i, Math.min(text.length(), i + size)));
            if (i + size >= text.length()) break;
        }
        return result;
    }
}
