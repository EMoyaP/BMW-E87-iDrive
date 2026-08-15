package com.ug.e87idrive;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pure scoring helper. It ranks observations; it never promotes them to verified vehicle mappings. */
final class DiagnosticCandidateClassifier {
    static final class Score {
        final int value;
        final String confidence;
        final String reason;

        Score(int value, String confidence, String reason) {
            this.value = value;
            this.confidence = confidence;
            this.reason = reason;
        }
    }

    private DiagnosticCandidateClassifier() {}

    static Score score(String sourceKind, String key, String baseline, String current,
                       int changes, int distinctValues, boolean stableVisible,
                       List<String> expectedTokens) {
        int score = 0;
        List<String> reasons = new ArrayList<>();

        if ("vehicle".equals(sourceKind)) {
            score += 34;
            reasons.add("fuente pública de vehículo");
        } else if ("broadcast".equals(sourceKind)) {
            score += 24;
            reasons.add("broadcast recibido pasivamente");
        } else if ("settings".equals(sourceKind)) {
            score += 18;
            reasons.add("ajuste Android observable");
        } else {
            score += 10;
            reasons.add("evento observable");
        }

        boolean changedFromBaseline = baseline == null ? !stableVisible : !same(baseline, current);
        if (changedFromBaseline) {
            score += 20;
            reasons.add("cambió frente a la línea base");
        } else if (stableVisible) {
            score += 20;
            reasons.add("valor estable ya visible");
        }

        if (changes > 0) {
            score += Math.min(24, changes * 8);
            reasons.add(changes + (changes == 1 ? " cambio" : " cambios"));
        }
        if (distinctValues >= 2) {
            score += Math.min(15, 5 + distinctValues * 3);
            reasons.add(distinctValues + " valores distintos");
        }

        String haystack = normalize(key + " " + current);
        int tokenMatches = 0;
        if (expectedTokens != null) {
            for (String token : expectedTokens) {
                String normalized = normalize(token);
                if (!normalized.isEmpty() && haystack.contains(normalized)) tokenMatches++;
            }
        }
        if (tokenMatches > 0) {
            score += Math.min(30, 15 + tokenMatches * 5);
            reasons.add("nombre compatible con la prueba");
        } else if (expectedTokens != null && !expectedTokens.isEmpty()
                && noisyWithoutSemanticMatch(haystack)) {
            score -= 35;
            reasons.add("posible ruido no relacionado");
        }

        score = Math.max(0, Math.min(99, score));
        String confidence = score >= 70 ? "FUERTE" : score >= 45 ? "MEDIO" : "DÉBIL";
        return new Score(score, confidence, joinReasons(reasons));
    }

    private static boolean noisyWithoutSemanticMatch(String text) {
        String[] noise = {"time", "clock", "battery", "volume", "media", "location", "gps", "speed"};
        for (String token : noise) if (text.contains(token)) return true;
        return false;
    }

    private static boolean same(String first, String second) {
        return first == null ? second == null : first.equals(second);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String joinReasons(List<String> reasons) {
        StringBuilder out = new StringBuilder();
        for (String reason : reasons) {
            if (out.length() > 0) out.append(" · ");
            out.append(reason);
        }
        return out.toString();
    }
}
