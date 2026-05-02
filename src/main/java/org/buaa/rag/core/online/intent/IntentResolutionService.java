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
     * 当所有子查询的候选意图总数超过 {@code budget} 时，按如下策略裁剪：
     * <ol>
     *   <li>每个子查询保留置信度最高的 1 个意图（保底配额）</li>
     *   <li>剩余配额按全局置信度降序补充</li>
     * </ol>
     */
    private List<SubQueryIntent> capTotalCandidates(List<SubQueryIntent> resolved, int budget) {
        // 快速路径：总数未超限时直接返回
        long totalCount = resolved.stream()
            .mapToLong(sq -> sq.candidates() == null ? 0 : sq.candidates().size())
            .sum();
        if (totalCount <= budget) {
            return resolved;
        }

        // ---- 第一轮：每个子查询保留 top-1 ----
        List<List<IntentDecision>> retained = new ArrayList<>(resolved.size());
        int usedSlots = 0;
        for (SubQueryIntent sq : resolved) {
            List<IntentDecision> c = sq.candidates();
            if (c != null && !c.isEmpty()) {
                retained.add(new ArrayList<>(List.of(c.get(0))));
                usedSlots++;
            } else {
                retained.add(new ArrayList<>());
            }
        }

        // ---- 第二轮：收集所有非 top-1 候选，按置信度降序排序 ----
        record ScoredEntry(int queryIdx, IntentDecision decision, double score) {}
        List<ScoredEntry> surplus = new ArrayList<>();
        for (int qi = 0; qi < resolved.size(); qi++) {
            List<IntentDecision> c = resolved.get(qi).candidates();
            if (c == null) continue;
            for (int ci = 1; ci < c.size(); ci++) {
                IntentDecision d = c.get(ci);
                double s = d.getConfidence() != null ? d.getConfidence() : 0.0;
                surplus.add(new ScoredEntry(qi, d, s));
            }
        }
        surplus.sort((a, b) -> Double.compare(b.score(), a.score()));

        // ---- 第三轮：按配额填充 ----
        int freeSlots = Math.max(0, budget - usedSlots);
        for (ScoredEntry entry : surplus) {
            if (freeSlots <= 0) break;
            retained.get(entry.queryIdx()).add(entry.decision());
            freeSlots--;
        }

        // ---- 重建结果列表 ----
        List<SubQueryIntent> trimmed = new ArrayList<>(resolved.size());
        for (int i = 0; i < resolved.size(); i++) {
            trimmed.add(new SubQueryIntent(resolved.get(i).subQuery(), List.copyOf(retained.get(i))));
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
