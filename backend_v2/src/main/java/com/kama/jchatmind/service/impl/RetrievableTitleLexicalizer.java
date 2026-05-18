package com.kama.jchatmind.service.impl;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class RetrievableTitleLexicalizer {
    private static final int MIN_TOKEN_LENGTH = 2;

    private RetrievableTitleLexicalizer() {
    }

    public static String normalize(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT)
                .replace("\r", " ")
                .replace("\n", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public static List<String> tokenize(String text) {
        Set<String> result = new LinkedHashSet<>();
        for (String token : collectTokens(text)) {
            result.add(token);
        }
        return new ArrayList<>(result);
    }

    public static List<String> tokenizeWithDuplicates(String text) {
        return new ArrayList<>(collectTokens(text));
    }

    public static String buildSearchText(String... texts) {
        List<String> result = new ArrayList<>();
        if (texts == null) {
            return "";
        }
        for (String text : texts) {
            result.addAll(collectTokens(text));
        }
        return String.join(" ", result);
    }

    public static String buildOrTsQuery(String text) {
        return String.join(" | ", tokenize(text));
    }

    private static List<String> collectTokens(String text) {
        List<String> result = new ArrayList<>();
        String normalized = normalize(text).replaceAll("[^\\p{IsHan}\\p{L}\\p{N}]+", " ");
        for (String token : normalized.split("\\s+")) {
            if (!StringUtils.hasText(token)) {
                continue;
            }
            if (containsHan(token)) {
                addCjkBigrams(result, token);
                continue;
            }
            if (token.length() >= MIN_TOKEN_LENGTH) {
                result.add(token);
            }
        }
        return result;
    }

    private static void addCjkBigrams(List<String> terms, String token) {
        int[] codePoints = token.codePoints().toArray();
        if (codePoints.length == 1) {
            terms.add(token);
            return;
        }
        for (int i = 0; i < codePoints.length - 1; i++) {
            terms.add(new String(codePoints, i, 2));
        }
    }

    private static boolean containsHan(String text) {
        return text.codePoints()
                .anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }
}
