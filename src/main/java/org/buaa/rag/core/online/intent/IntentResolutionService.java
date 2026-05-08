package org.buaa.rag.core.online.intent;

import static org.buaa.rag.tool.TextUtils.compact;
import static org.buaa.rag.tool.TimingUtils.elapsedMs;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.buaa.rag.core.model.IntentDecision;
import org.buaa.rag.core.online.trace.RagTraceNode;
import org.buaa.rag.properties.IntentResolutionProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class IntentResolutionService {

    private static final Logger log = LoggerFactory.getLogger(IntentResolutionService.class);
    private static final String INTEGRATED_INTENT_NODE_ID = "kb_integrated";
    private static final long INTEGRATED_KNOWLEDGE_BASE_ID = 108L;
    private static final String INTEGRATED_INTENT_NAME = "综合规章";

    private final IntentRouterService intentRouterService;
    private final IntentTreeSnapshotService intentTreeSnapshotService;
    private final IntentResolutionProperties properties;
    private final Executor intentResolutionExecutor;

    public IntentResolutionService(IntentRouterService intentRouterService,
                                   IntentTreeSnapshotService intentTreeSnapshotService,
                                   IntentResolutionProperties properties,
                                   @Qualifier("intentResolutionExecutor") Executor intentResolutionExecutor) {
        this.intentRouterService = intentRouterService;
        this.intentTreeSnapshotService = intentTreeSnapshotService;
        this.properties = properties;
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
        int perQueryMax = Math.max(1, properties.getPerQueryMaxCandidates());
        double minScore = Math.max(0.0, properties.getMinScore());
        List<IntentDecision> ranked = intentRouterService.rankIntentCandidates(userId, query, perQueryMax, minScore);
        return ensureIntegratedFallback(ranked);
    }

    private SubQueryIntent classifySingle(String userId, String subQuery) {
        List<IntentDecision> candidates = resolveForQuery(userId, subQuery);
        log.info("子问题意图命中 | query='{}' | candidates={}",
            compact(subQuery), summarizeCandidates(candidates));
        return new SubQueryIntent(subQuery, candidates);
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
        List<IntentDecision> integratedFallbacks = new ArrayList<>(resolved.size());
        List<SubQueryIntent> regularResolved = stripIntegratedFallbacks(resolved, integratedFallbacks);

        long totalCount = regularResolved.stream()
                .mapToLong(sq -> sq.candidates() == null ? 0 : sq.candidates().size())
                .sum();
        if (totalCount <= budget) {
            return appendIntegratedFallbacks(regularResolved, integratedFallbacks);
        }

        int queryCount = regularResolved.size();
        // ---- 第一步：计算各子查询的质量分（候选置信度之和）用于按比例分配 ----
        double[] qualityScores = new double[queryCount];
        int[] originalSizes = new int[queryCount];
        double totalQuality = 0.0;
        for (int i = 0; i < queryCount; i++) {
            List<IntentDecision> c = regularResolved.get(i).candidates();
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
            List<IntentDecision> c = regularResolved.get(i).candidates();
            if (c == null || c.isEmpty() || quotas[i] == 0) {
                trimmed.add(new SubQueryIntent(regularResolved.get(i).subQuery(), List.of()));
            } else {
                List<IntentDecision> kept = List.copyOf(c.subList(0, Math.min(quotas[i], c.size())));
                trimmed.add(new SubQueryIntent(regularResolved.get(i).subQuery(), kept));
            }
        }
        return appendIntegratedFallbacks(trimmed, integratedFallbacks);
    }

    private List<IntentDecision> ensureIntegratedFallback(List<IntentDecision> ranked) {
        if (ranked == null || ranked.isEmpty()) {
            IntentDecision fallback = buildIntegratedFallbackDecision();
            return fallback != null ? List.of(fallback) : List.of(defaultRagFallback());
        }
        if (isPureChat(ranked) || containsIntegratedFallback(ranked)) {
            return ranked;
        }
        IntentDecision fallback = buildIntegratedFallbackDecision();
        if (fallback == null) {
            return ranked;
        }
        List<IntentDecision> merged = new ArrayList<>(ranked.size() + 1);
        merged.addAll(ranked);
        merged.add(fallback);
        return List.copyOf(merged);
    }

    private IntentDecision buildIntegratedFallbackDecision() {
        intentTreeSnapshotService.loadTree();
        return intentTreeSnapshotService.getById(INTEGRATED_INTENT_NODE_ID)
            .map(node -> IntentDecision.builder()
                .level1(StringUtils.hasText(node.getNodeName()) ? node.getNodeName().trim() : INTEGRATED_INTENT_NAME)
                .level2(StringUtils.hasText(node.getNodeName()) ? node.getNodeName().trim() : INTEGRATED_INTENT_NAME)
                .knowledgeBaseId(node.getKnowledgeBaseId() != null ? node.getKnowledgeBaseId() : INTEGRATED_KNOWLEDGE_BASE_ID)
                .promptTemplate(node.getPromptTemplate())
                .promptSnippet(node.getPromptSnippet())
                .topK(node.getTopK())
                .action(IntentDecision.Action.ROUTE_RAG)
                .strategy(IntentDecision.Strategy.HYBRID)
                .confidence(0.01D)
                .build())
            .orElseGet(() -> IntentDecision.builder()
                .level1(INTEGRATED_INTENT_NAME)
                .level2(INTEGRATED_INTENT_NAME)
                .knowledgeBaseId(INTEGRATED_KNOWLEDGE_BASE_ID)
                .action(IntentDecision.Action.ROUTE_RAG)
                .strategy(IntentDecision.Strategy.HYBRID)
                .confidence(0.01D)
                .build());
    }

    private IntentDecision defaultRagFallback() {
        return IntentDecision.builder()
            .action(IntentDecision.Action.ROUTE_RAG)
            .strategy(IntentDecision.Strategy.HYBRID)
            .build();
    }

    private boolean isPureChat(List<IntentDecision> candidates) {
        boolean hasCandidate = false;
        for (IntentDecision candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            hasCandidate = true;
            if (candidate.getAction() != IntentDecision.Action.ROUTE_CHAT) {
                return false;
            }
        }
        return hasCandidate;
    }

    private boolean containsIntegratedFallback(List<IntentDecision> candidates) {
        for (IntentDecision candidate : candidates) {
            if (isIntegratedFallback(candidate)) {
                return true;
            }
        }
        return false;
    }

    private boolean isIntegratedFallback(IntentDecision candidate) {
        if (candidate == null || candidate.getAction() != IntentDecision.Action.ROUTE_RAG) {
            return false;
        }
        if (Long.valueOf(INTEGRATED_KNOWLEDGE_BASE_ID).equals(candidate.getKnowledgeBaseId())) {
            return true;
        }
        return INTEGRATED_INTENT_NAME.equals(candidate.getLevel1())
            && INTEGRATED_INTENT_NAME.equals(candidate.getLevel2());
    }

    private List<SubQueryIntent> stripIntegratedFallbacks(List<SubQueryIntent> resolved,
                                                          List<IntentDecision> integratedFallbacks) {
        List<SubQueryIntent> regularResolved = new ArrayList<>(resolved.size());
        for (SubQueryIntent subQueryIntent : resolved) {
            List<IntentDecision> candidates = subQueryIntent.candidates();
            IntentDecision integrated = null;
            List<IntentDecision> regular = new ArrayList<>();
            if (candidates != null) {
                for (IntentDecision candidate : candidates) {
                    if (integrated == null && isIntegratedFallback(candidate)) {
                        integrated = candidate;
                        continue;
                    }
                    regular.add(candidate);
                }
            }
            integratedFallbacks.add(integrated);
            regularResolved.add(new SubQueryIntent(subQueryIntent.subQuery(), List.copyOf(regular)));
        }
        return regularResolved;
    }

    private List<SubQueryIntent> appendIntegratedFallbacks(List<SubQueryIntent> resolved,
                                                           List<IntentDecision> integratedFallbacks) {
        List<SubQueryIntent> merged = new ArrayList<>(resolved.size());
        for (int i = 0; i < resolved.size(); i++) {
            SubQueryIntent subQueryIntent = resolved.get(i);
            IntentDecision integrated = i < integratedFallbacks.size() ? integratedFallbacks.get(i) : null;
            List<IntentDecision> candidates = subQueryIntent.candidates();
            if (integrated == null) {
                merged.add(subQueryIntent);
                continue;
            }
            List<IntentDecision> rebuilt = new ArrayList<>(candidates == null ? 1 : candidates.size() + 1);
            if (candidates != null && !candidates.isEmpty()) {
                rebuilt.addAll(candidates);
            }
            rebuilt.add(integrated);
            merged.add(new SubQueryIntent(subQueryIntent.subQuery(), List.copyOf(rebuilt)));
        }
        return merged;
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

}
