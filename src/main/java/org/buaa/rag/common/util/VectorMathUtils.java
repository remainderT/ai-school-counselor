package org.buaa.rag.common.util;

/**
 * Vector math helpers shared across services that need cosine similarity.
 */
public final class VectorMathUtils {

    private VectorMathUtils() {
        // utility class
    }

    /**
     * Compute cosine similarity between two float vectors and map the raw
     * cosine value from [-1, 1] to [0, 1].
     *
     * @return similarity in [0, 1], or 0.0 for degenerate inputs
     */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0 || a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        double cos = dot / (Math.sqrt(normA) * Math.sqrt(normB));
        if (Double.isNaN(cos) || Double.isInfinite(cos)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, (cos + 1.0) / 2.0));
    }
}
