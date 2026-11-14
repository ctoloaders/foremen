package com.foremen.service;

import java.text.Normalizer;
import java.util.Map;
import java.util.function.Predicate;

/**
 * FOR-11-00 (R20) — pure derivation of a seller code from a display name: lowercase, ASCII-folded
 * (Polish diacritics, Cyrillic transliteration), non {@code [a-z0-9]} runs collapsed to {@code _},
 * trimmed to {@value #BASE_MAX} chars, with {@code _2}, {@code _3}, ... appended on collision.
 */
public final class SellerCodeGenerator {

    static final int BASE_MAX = 90;
    static final String FALLBACK = "seller";

    private static final Map<Character, String> CYRILLIC = Map.ofEntries(
            Map.entry('а', "a"), Map.entry('б', "b"), Map.entry('в', "v"), Map.entry('г', "g"),
            Map.entry('д', "d"), Map.entry('е', "e"), Map.entry('ё', "e"), Map.entry('ж', "zh"),
            Map.entry('з', "z"), Map.entry('и', "i"), Map.entry('й', "y"), Map.entry('к', "k"),
            Map.entry('л', "l"), Map.entry('м', "m"), Map.entry('н', "n"), Map.entry('о', "o"),
            Map.entry('п', "p"), Map.entry('р', "r"), Map.entry('с', "s"), Map.entry('т', "t"),
            Map.entry('у', "u"), Map.entry('ф', "f"), Map.entry('х', "h"), Map.entry('ц', "ts"),
            Map.entry('ч', "ch"), Map.entry('ш', "sh"), Map.entry('щ', "sch"), Map.entry('ъ', ""),
            Map.entry('ы', "y"), Map.entry('ь', ""), Map.entry('э', "e"), Map.entry('ю', "yu"),
            Map.entry('я', "ya"));

    private SellerCodeGenerator() {}

    /** Base slug without collision handling; never empty, always matches {@code ^[a-z0-9_]{1,90}$}. */
    public static String slug(String name) {
        StringBuilder folded = new StringBuilder();
        String lower = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        for (char c : lower.toCharArray()) {
            if (c == 'ł') {
                folded.append('l');
            } else if (CYRILLIC.containsKey(c)) {
                folded.append(CYRILLIC.get(c));
            } else {
                folded.append(c);
            }
        }
        String ascii = Normalizer.normalize(folded, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        String slug = ascii.replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (slug.length() > BASE_MAX) {
            slug = slug.substring(0, BASE_MAX).replaceAll("_+$", "");
        }
        return slug.isEmpty() ? FALLBACK : slug;
    }

    /** Returns the first of {@code base}, {@code base_2}, {@code base_3}, ... for which {@code taken} is false. */
    public static String generate(String name, Predicate<String> taken) {
        String base = slug(name);
        if (!taken.test(base)) {
            return base;
        }
        for (int i = 2; ; i++) {
            String candidate = base + "_" + i;
            if (!taken.test(candidate)) {
                return candidate;
            }
        }
    }
}
