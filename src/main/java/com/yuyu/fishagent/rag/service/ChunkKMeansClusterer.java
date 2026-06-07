package com.yuyu.fishagent.rag.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档切片向量的轻量 K-Means 聚类器。
 *
 * <p>这里不引入重型 ML 依赖：先把向量归一化，之后用欧氏距离聚类；对单位向量来说，
 * 欧氏距离与余弦距离排序等价，适合 ES dense_vector 的 cosine 语义。</p>
 */
final class ChunkKMeansClusterer {

    private ChunkKMeansClusterer() {
    }

    static int[] cluster(List<List<Float>> vectors, int requestedK, int maxIterations) {
        if (vectors == null || vectors.isEmpty()) {
            return new int[0];
        }
        int n = vectors.size();
        int k = Math.min(n, Math.max(1, requestedK));
        if (k <= 1) {
            return new int[n];
        }

        List<double[]> normalized = vectors.stream()
                .map(ChunkKMeansClusterer::normalize)
                .toList();
        int dim = normalized.get(0).length;
        List<double[]> centroids = initialCentroids(normalized, k);
        int[] labels = new int[n];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = -1;
        }

        int iterations = Math.max(1, maxIterations);
        for (int round = 0; round < iterations; round++) {
            boolean changed = assignLabels(normalized, centroids, labels);
            List<double[]> next = recomputeCentroids(normalized, labels, k, dim, centroids);
            centroids = next;
            if (!changed) {
                break;
            }
        }
        return labels;
    }

    private static boolean assignLabels(List<double[]> vectors, List<double[]> centroids, int[] labels) {
        boolean changed = false;
        for (int i = 0; i < vectors.size(); i++) {
            int best = 0;
            double bestDistance = Double.MAX_VALUE;
            for (int c = 0; c < centroids.size(); c++) {
                double distance = squaredDistance(vectors.get(i), centroids.get(c));
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = c;
                }
            }
            if (labels[i] != best) {
                labels[i] = best;
                changed = true;
            }
        }
        return changed;
    }

    private static List<double[]> recomputeCentroids(List<double[]> vectors, int[] labels, int k, int dim, List<double[]> oldCentroids) {
        double[][] sums = new double[k][dim];
        int[] counts = new int[k];
        for (int i = 0; i < vectors.size(); i++) {
            int label = labels[i];
            counts[label]++;
            double[] vector = vectors.get(i);
            for (int d = 0; d < dim; d++) {
                sums[label][d] += vector[d];
            }
        }

        List<double[]> centroids = new ArrayList<>(k);
        for (int c = 0; c < k; c++) {
            if (counts[c] == 0) {
                centroids.add(oldCentroids.get(c));
                continue;
            }
            for (int d = 0; d < dim; d++) {
                sums[c][d] /= counts[c];
            }
            centroids.add(normalize(sums[c]));
        }
        return centroids;
    }

    /**
     * 确定性初始化：首个质心取首向量，后续每次取与已选质心距离最大的向量，避免随机种子导致测试和缓存不稳定。
     */
    private static List<double[]> initialCentroids(List<double[]> vectors, int k) {
        List<double[]> centroids = new ArrayList<>();
        centroids.add(vectors.get(0));
        while (centroids.size() < k) {
            double maxMinDistance = -1.0;
            int bestIndex = 0;
            for (int i = 0; i < vectors.size(); i++) {
                double minDistance = Double.MAX_VALUE;
                for (double[] centroid : centroids) {
                    minDistance = Math.min(minDistance, squaredDistance(vectors.get(i), centroid));
                }
                if (minDistance > maxMinDistance) {
                    maxMinDistance = minDistance;
                    bestIndex = i;
                }
            }
            centroids.add(vectors.get(bestIndex));
        }
        return centroids;
    }

    private static double[] normalize(List<Float> values) {
        double[] vector = new double[values == null ? 0 : values.size()];
        if (values != null) {
            for (int i = 0; i < values.size(); i++) {
                vector[i] = values.get(i) == null ? 0.0 : values.get(i);
            }
        }
        return normalize(vector);
    }

    private static double[] normalize(double[] vector) {
        double norm = 0.0;
        for (double value : vector) {
            norm += value * value;
        }
        if (norm == 0.0) {
            return vector;
        }
        double scale = Math.sqrt(norm);
        double[] out = new double[vector.length];
        for (int i = 0; i < vector.length; i++) {
            out[i] = vector[i] / scale;
        }
        return out;
    }

    private static double squaredDistance(double[] a, double[] b) {
        int len = Math.min(a.length, b.length);
        double sum = 0.0;
        for (int i = 0; i < len; i++) {
            double diff = a[i] - b[i];
            sum += diff * diff;
        }
        return sum;
    }
}
