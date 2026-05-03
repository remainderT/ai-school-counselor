package org.buaa.rag.core.online.intent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.buaa.rag.core.model.IntentDecision;
import org.buaa.rag.core.trace.RagTraceNode;
import org.buaa.rag.properties.IntentRoutingProperties;
import org.buaa.rag.properties.IntentResolutionProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class IntentResolutionService {

    private static final Logger log = LoggerFactory.getLogger(IntentResolutionService.class);

    private final IntentRouterService intentRouterService;
    private final IntentPatternService intentPatternService;
    private final IntentResolutionProperties properties;
    private final IntentRoutingProperties intentRoutingProperties;
    private final Executor intentResolutionExecutor;

    public IntentResolutionService(IntentRouterService intentRouterService,
                                   IntentPatternService intentPatternService,
                                   IntentResolutionProperties properties,
                                   IntentRoutingProperties intentRoutingProperties,
                                   @Qualifier("intentResolutionExecutor") Executor intentResolutionExecutor) {
        this.intentRouterService = intentRouterService;
        this.intentPatternService = intentPatternService;
        this.properties = properties;
        this.intentRoutingProperties = intentRoutingProperties;
        this.intentResolutionExecutor = intentResolutionExecutor;
    }

    @RagTraceNode(name = "intent-resolve", type = "INTENT")
    public List<SubQueryIntent> resolve(String userId, List<String> subQueries) {
        long start = System.nanoTime();
        if (subQueries == null || subQueries.isEmpty()) {
            return List.of();
        }

        List<String> normalized = subQueries.stream()
            .filter(StringUtils::hasText)
            .map(String::trim)
            .toList();
        if (normalized.isEmpty()) {
            return List.of();
        }

        List<CompletableFuture<SubQueryIntent>> futures = normalized.stream()
            .map(subQuery -> CompletableFuture.supplyAsync(
                () -> classifySingle(userId, subQuery),
                intentResolutionExecutor
            ))
            .toList();

        List<SubQueryIntent> resolved = futures.stream()
            .map(CompletableFuture::join)
            .toList();
        List<SubQueryIntent> capped = capTotalCandidates(resolved, Math.max(1, properties.getMaxTotalCandidates()));
        log.info("子问题意图解析完成 | 子问题数={} | 总候选数={} | 耗时={}ms",
            capped.size(),
            capped.stream().mapToInt(item -> item.candidates() == null ? 0 : item.candidates().size()).sum(),
            elapsedMs(start));
        return capped;
    }

    public List<IntentDecision> resolveForQuery(String userId, String query) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        var semanticHit = intentPatternService.semanticRoute(query);
        if (semanticHit.isPresent()) {
            IntentDecision decision = semanticHit.get();
            Double confidence = decision.getConfidence();
            if (confidence != null && confidence >= intentRoutingProperties.getSemanticDirectThreshold()) {
                return List.of(decision);
            }
        }
        int perQueryMax = Math.max(1, properties.getPerQueryMaxCandidates());
        double minScore = Math.max(0.0, properties.getMinScore());
        List<IntentDecision> ranked = intentRouterService.rankIntentCandidates(userId, query, perQueryMax, minScore);
        if (semanticHit.isPresent()) {
            ranked = mergeSemanticHit(semanticHit.get(), ranked, perQueryMax);
        }
        if (!ranked.isEmpty()) {
            return ranked;
        }
        return List.of(IntentDecision.builder()
            .action(IntentDecision.Action.ROUTE_RAG)
            .strategy(IntentDecision.Strategy.HYBRID)
            .build());
    }

    private SubQueryIntent classifySingle(String userId, String subQuery) {
        List<IntentDecision> candidates = resolveForQuery(userId, subQuery);
        log.info("子问题意图命中 | query='{}' | candidates={}",
            compact(subQuery), summarizeCandidates(candidates));
        return new SubQueryIntent(subQuery, candidates);
    }

    private List<IntentDecision> mergeSemanticHit(IntentDecision semanticHit,
                                                  List<IntentDecision> ranked,
                                                  int perQueryMax) {
        if (semanticHit == null) {
            return ranked;
        }
        List<IntentDecision> merged = new ArrayList<>();
        merged.add(semanticHit);
        if (ranked != null) {
            for (IntentDecision candidate : ranked) {
                if (candidate == null) {
                    continue;
                }
                if (sameIntent(semanticHit, candidate)) {
                    continue;
                }
                merged.add(candidate);
                if (merged.size() >= perQueryMax) {
                    break;
                }
            }
        }
        return merged;
    }

    private boolean sameIntent(IntentDecision left, IntentDecision right) {
        if (left == null || right == null) {
            return false;
        }
        return safeEquals(left.getAction(), right.getAction())
            && safeEquals(left.getLevel1(), right.getLevel1())
            && safeEquals(left.getLevel2(), right.getLevel2())
            && safeEquals(left.getToolName(), right.getToolName());
    }

    private boolean safeEquals(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }

    /**
     * 当所有子查询的候选意图总数超过 {@code budget} 时，按比例分配策略裁剪。
     *
     * <p>算法分三步：
     * <ol>
     *   <li>计算每个子查询的「意图质量分」（所有候选置信度之和），
     *       按质量分占比为每个子查询分配配额（至少 1 个）</li>
     *   <li>各子查询在自身配额内保留置信度最高的 N 个候选</li>
     *   <li>若分配后有余量（因向下取整产生），将剩余配额优先补给
     *       被截断最多的子查询</li>
     * </ol>
     *
     * <p>相比全局排序策略，比例分配能更公平地保证每个子查询的覆盖度，
     * 避免某个高置信子查询独占全部配额导致其他子查询失去表达。
     */
    private List<SubQueryIntent> capTotalCandidates(List<SubQueryIntent> resolved, int budget) {
        long totalCount = resolved.stream()
                .mapToLong(sq -> sq.candidates() == null ? 0 : sq.candidates().size())
                .sum();
        if (totalCount <= budget) {
            return resolved;
        }

        int queryCount = resolved.size();
        // ---- 第一步：计算各子查询的质量分（候选置信度之和）用于按比例分配 ----
        double[] qualityScores = new double[queryCount];
        int[] originalSizes = new int[queryCount];
        double totalQuality = 0.0;
        for (int i = 0; i < queryCount; i++) {
            List<IntentDecision> c = resolved.get(i).candidates();
            originalSizes[i] = (c == null) ? 0 : c.size();
            if (c != null) {
                for (IntentDecision d : c) {
                    double s = (d != null && d.getConfidence() != null) ? d.getConfidence() : 0.0;
                    qualityScores[i] += s;
                }
            }
            totalQuality += qualityScores[i];
        }

        // ---- 第二步：按质量分占比分配配额（每个子查询至少保留 1 个） ----
        int[] quotas = new int[queryCount];
        int allocated = 0;
        for (int i = 0; i < queryCount; i++) {
            if (originalSizes[i] == 0) {
                quotas[i] = 0;
            } else {
                double ratio = totalQuality > 0 ? qualityScores[i] / totalQuality : 1.0 / queryCount;
                quotas[i] = Math.max(1, (int) Math.floor(ratio * budget));
                // 配额不应超过该子查询原始候选数
                quotas[i] = Math.min(quotas[i], originalSizes[i]);
            }
            allocated += quotas[i];
        }

        // ---- 第三步：将余量补给被截断最多的子查询 ----
        int remaining = budget - allocated;
        if (remaining > 0) {
            // 按「被截断数量」降序排列，优先补给损失最大的子查询
            List<Integer> byTruncation = new ArrayList<>();
            for (int i = 0; i < queryCount; i++) {
                if (quotas[i] < originalSizes[i]) {
                    byTruncation.add(i);
                }
            }
            byTruncation.sort((a, b) ->
                    Integer.compare(originalSizes[b] - quotas[b], originalSizes[a] - quotas[a]));

            for (int idx : byTruncation) {
                if (remaining <= 0) break;
                int canAdd = originalSizes[idx] - quotas[idx];
                int toAdd = Math.min(canAdd, remaining);
                quotas[idx] += toAdd;
                remaining -= toAdd;
            }
        }

        // ---- 重建结果（各子查询按配额截取前 N 个） ----
        List<SubQueryIntent> trimmed = new ArrayList<>(queryCount);
        for (int i = 0; i < queryCount; i++) {
            List<IntentDecision> c = resolved.get(i).candidates();
            if (c == null || c.isEmpty() || quotas[i] == 0) {
                trimmed.add(new SubQueryIntent(resolved.get(i).subQuery(), List.of()));
            } else {
                List<IntentDecision> kept = List.copyOf(c.subList(0, Math.min(quotas[i], c.size())));
                trimmed.add(new SubQueryIntent(resolved.get(i).subQuery(), kept));
            }
        }
        return trimmed;
    }

    private String summarizeCandidates(List<IntentDecision> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return "[]";
        }
        List<String> list = candidates.stream()
            .map(this::describeDecision)
            .toList();
        return list.toString();
    }

    private String describeDecision(IntentDecision d) {
        if (d == null) {
            return "null";
        }
        return "{action=" + d.getAction()
            + ",level1=" + d.getLevel1()
            + ",level2=" + d.getLevel2()
            + ",tool=" + d.getToolName()
            + ",score=" + d.getConfidence()
            + "}";
    }

    private String compact(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() > 120 ? normalized.substring(0, 120) + "..." : normalized;
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
