package com.yuyu.fishagent.common.util;

/**
 * Lightweight local token estimator for prompt budgeting.
 * <p>
 * The estimator intentionally avoids model/API calls. It uses separate density
 * assumptions for CJK and Latin-like text, then leaves a safety margin at the
 * caller/allocator layer to absorb model-specific tokenizer differences.
 * </p>
 */
public final class TokenEstimator {

    private TokenEstimator() {
    }

    /**
     * Estimate the token count for mixed CJK/Latin text.
     *
     * @param text text to estimate, may be {@code null}
     * @return estimated token count, never negative
     */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int total = text.length();
        for (int i = 0; i < total; i++) {
            if (isCjk(text.charAt(i))) {
                cjk++;
            }
        }
        int latinLike = total - cjk;
        return (int) Math.ceil(cjk / 1.5 + latinLike / 4.0);
    }

    private static boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA;
    }
}
