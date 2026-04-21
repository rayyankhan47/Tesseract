package com.rayyan.tesseract.toolbox;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls user-authored {@code def} blocks out of a sandbox script for
 * the {@code ToolPromoter} to evaluate (§6.3.1).
 *
 * <p>We intentionally do <em>not</em> use the sandbox parser here.
 * The parser throws on first error, and the promoter should still be
 * able to survey the definitions even if later code in the script
 * raised at runtime. A simple indent-based scanner is enough for the
 * sandbox's Python subset.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Split the source into lines.</li>
 *   <li>For each line matching {@code ^(\s*)def NAME(ARGS):}, capture
 *       the leading indentation width.</li>
 *   <li>The block extends from that line forward until the first
 *       non-blank, non-comment line whose indent is less-or-equal
 *       to the def's indent.</li>
 * </ol>
 *
 * <p>Blank lines and comments inside the block are preserved so the
 * promoter sees the original style. Top-level defs are the only ones
 * considered: nested functions inside other defs are skipped because
 * they rely on closure state that doesn't promote cleanly.
 */
public final class UserDefExtractor {

    private static final Pattern DEF_LINE =
            Pattern.compile("^(\\s*)def\\s+([A-Za-z_][A-Za-z_0-9]*)\\s*\\(([^)]*)\\)\\s*:\\s*(?:#.*)?$");

    private UserDefExtractor() {}

    /**
     * Parsed representation of a top-level {@code def} block, carrying
     * enough metadata for the ToolPromoter prompt.
     */
    public record UserDef(String name, String signature, List<String> params, String source) {
        public UserDef {
            params = params == null ? List.of() : List.copyOf(params);
        }
    }

    /**
     * Returns every top-level user-defined function in the script.
     * Order matches source order. Malformed defs (truncated signatures
     * etc.) are silently skipped — the sandbox would already have
     * rejected them before this runs.
     */
    public static List<UserDef> extract(String source) {
        List<UserDef> out = new ArrayList<>();
        if (source == null || source.isEmpty()) return out;
        String[] lines = source.split("\\r?\\n", -1);

        for (int i = 0; i < lines.length; i++) {
            Matcher m = DEF_LINE.matcher(lines[i]);
            if (!m.matches()) continue;
            int defIndent = leadingSpaces(lines[i]);
            if (defIndent != 0) continue; // only top-level defs (§6.3.1)

            String name = m.group(2);
            String argsRaw = m.group(3).trim();
            List<String> params = parseParams(argsRaw);
            String signature = name + "(" + argsRaw + ")";

            int end = findBlockEnd(lines, i + 1, defIndent);
            StringBuilder sb = new StringBuilder();
            for (int j = i; j < end; j++) {
                sb.append(lines[j]);
                if (j < end - 1) sb.append('\n');
            }
            out.add(new UserDef(name, signature, params, sb.toString()));
            i = end - 1;
        }
        return out;
    }

    private static int findBlockEnd(String[] lines, int startIdx, int defIndent) {
        int last = startIdx;
        for (int j = startIdx; j < lines.length; j++) {
            String line = lines[j];
            if (isBlankOrComment(line)) continue;
            int indent = leadingSpaces(line);
            if (indent <= defIndent) return last + 1;
            last = j;
        }
        return lines.length;
    }

    private static int leadingSpaces(String line) {
        int n = 0;
        while (n < line.length()) {
            char c = line.charAt(n);
            if (c == ' ' || c == '\t') n++;
            else break;
        }
        return n;
    }

    private static boolean isBlankOrComment(String line) {
        String trimmed = line.strip();
        return trimmed.isEmpty() || trimmed.startsWith("#");
    }

    private static List<String> parseParams(String raw) {
        if (raw.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String piece : raw.split(",")) {
            String p = piece.trim();
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }
}
