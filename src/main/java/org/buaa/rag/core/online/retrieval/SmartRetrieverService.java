package org.buaa.rag.core.online.retrieval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.buaa.rag.properties.EsProperties;
import org.buaa.rag.properties.RagProperties;
import org.buaa.rag.dao.entity.DocumentDO;
import org.buaa.rag.dao.entity.ESIndexDO;
import org.buaa.rag.dao.entity.KnowledgeDO;
import org.buaa.rag.dao.mapper.DocumentMapper;
import org.buaa.rag.dao.mapper.KnowledgeMapper;
import org.buaa.rag.core.model.RetrievalMatch;
import org.buaa.rag.core.offline.index.VectorEncoding;
import org.buaa.rag.tool.KnowledgeNameConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * 智能检索服务
 * 结合向量检索和文本匹配的混合搜索策略
 */
@Slf4j
@Service
public class SmartRetrieverService {

    private static final int MAX_RECALL_SIZE = 300;
    private static final long PARALLEL_SEARCH_TIMEOUT_SECONDS = 30L;

    private final ElasticsearchClient esClient;
    private final VectorEncoding encodingService;
    private final DocumentMapper documentMapper;
    private final KnowledgeMapper knowledgeMapper;
    private final RagProperties ragProperties;
    private final EsProperties esProperties;
    private final MilvusRetrieverService milvusRetrieverService;
    private final Executor retrievalExecutor;

    public SmartRetrieverService(ElasticsearchClient esClient,
                                 VectorEncoding encodingService,
                                 DocumentMapper documentMapper,
                                 KnowledgeMapper knowledgeMapper,
                                 RagProperties ragProperties,
                                 EsProperties esProperties,
                                 MilvusRetrieverService milvusRetrieverService,
                                 @Qualifier("retrievalChannelExecutor") Executor retrievalExecutor) {
        this.esClient = esClient;
        this.encodingService = encodingService;
        this.documentMapper = documentMapper;
        this.knowledgeMapper = knowledgeMapper;
        this.ragProperties = ragProperties;
        this.esProperties = esProperties;
        this.milvusRetrieverService = milvusRetrieverService;
        this.retrievalExecutor = retrievalExecutor;
    }

    public List<RetrievalMatch> retrieve(String queryText, int topK, String userId) {
        try {
            log.debug("执行混合检索 - 查询: {}, K值: {}", queryText, topK);

            // 生成查询向量
            final List<Float> queryVector = generateQueryVector(queryText);

            // 向量生成失败则降级到纯文本检索
            if (queryVector == null) {
                log.warn("向量生成失败，降级为纯文本检索");
                return performTextOnlyRetrieval(queryText, topK, userId);
            }

            List<RetrievalMatch> matches = performHybridRetrieval(queryText, queryVector, topK);

            return filterAndEnrichMatches(matches, userId, topK);
        } catch (Exception e) {
            if (isIndexMissing(e)) {
                log.warn("索引 {} 不存在，返回空结果", esProperties.getIndex());
                return Collections.emptyList();
            }
            log.error("检索失败", e);
            throw new RuntimeException("检索过程发生异常", e);
        }
    }

    public List<RetrievalMatch> retrieveVectorOnly(String queryText, int topK, String userId) {
        try {
            List<Float> vector = generateQueryVector(queryText);
            if (vector == null) {
                return Collections.emptyList();
            }

            List<RetrievalMatch> results = performVectorOnlyRetrievalRaw(queryText, vector, topK);
            return filterAndEnrichMatches(results, userId, topK);
        } catch (Exception e) {
            log.error("向量检索失败", e);
            throw new RuntimeException("向量检索过程发生异常", e);
        }
    }

    public List<RetrievalMatch> retrieveTextOnly(String queryText,
                                                 int topK,
                                                 String userId) {
        try {
            return performTextOnlyRetrieval(queryText, topK, userId);
        } catch (Exception e) {
            if (isIndexMissing(e)) {
                log.warn("索引 {} 不存在，文本检索返回空结果", esProperties.getIndex());
                return Collections.emptyList();
            }
            log.error("文本检索失败，降级返回空结果", e);
            return Collections.emptyList();
        }
    }

    /**
     * 在指定知识库集合中检索（意图定向）。
     * <p>
     * 直接在对应知识库的 Milvus Collection 中做向量检索，并用 ES 文本检索结果做 RRF 融合后过滤，
     * 避免全局检索后再过滤的额外开销和跨库噪声。
     */
    public List<RetrievalMatch> retrieveScoped(String queryText,
                                               int topK,
                                               String userId,
                                               Set<Long> knowledgeIds) {
        if (knowledgeIds == null || knowledgeIds.isEmpty()) {
            return retrieve(queryText, topK, userId);
        }

        // 查出各知识库的 collectionName
        List<KnowledgeDO> knowledgeList = knowledgeMapper.selectList(
            Wrappers.lambdaQuery(KnowledgeDO.class)
                .in(KnowledgeDO::getId, knowledgeIds)
                .eq(KnowledgeDO::getDelFlag, 0)
        );

        List<Float> queryVector = generateQueryVector(queryText);
        int recallSize = calculateRecallSize(queryText, topK);

        // 并行：向量检索 + 文本检索同时执行
        List<RetrievalMatch> vectorMatches = queryVector != null
            ? searchCollectionsParallel(knowledgeList, queryVector, recallSize)
            : Collections.emptyList();

        List<RetrievalMatch> textMatches;
        try {
            textMatches = performTextOnlyRetrievalRaw(queryText, recallSize);
        } catch (Exception e) {
            textMatches = Collections.emptyList();
        }

        // RRF 融合
        List<RetrievalMatch> fused = fuseRetrievalMatches(List.of(textMatches, vectorMatches), topK);

        // 过滤只保留属于目标知识库的结果
        Set<String> md5s = fused.stream()
            .map(RetrievalMatch::getFileMd5)
            .filter(md5 -> md5 != null && !md5.isBlank())
            .collect(Collectors.toSet());

        if (md5s.isEmpty()) {
            return List.of();
        }

        List<DocumentDO> docs = documentMapper.selectList(
            Wrappers.lambdaQuery(DocumentDO.class)
                .in(DocumentDO::getMd5Hash, md5s)
                .eq(DocumentDO::getDelFlag, 0)
        );
        Set<String> allowed = docs.stream()
            .filter(doc -> doc.getKnowledgeId() != null && knowledgeIds.contains(doc.getKnowledgeId()))
            .map(DocumentDO::getMd5Hash)
            .collect(Collectors.toSet());

        List<RetrievalMatch> scoped = fused.stream()
            .filter(match -> allowed.contains(match.getFileMd5()))
            .collect(Collectors.toList());

        return filterAndEnrichMatches(scoped, userId, topK);
    }

    /**
     * 执行混合检索（全局，跨所有知识库）
     */
    private List<RetrievalMatch> performHybridRetrieval(String query,
                                                        List<Float> vector,
                                                        int topK) throws Exception {
        int recallSize = calculateRecallSize(query, topK);
        List<RetrievalMatch> textMatches = performTextOnlyRetrievalRaw(query, recallSize);
        List<RetrievalMatch> vectorMatches = searchAllCollections(vector, recallSize);
        return fuseRetrievalMatches(List.of(textMatches, vectorMatches), topK);
    }

    /**
     * 跨所有知识库 Collection 并行进行向量检索并合并结果。
     */
    private List<RetrievalMatch> searchAllCollections(List<Float> vector, int topK) {
        List<KnowledgeDO> allKnowledge = knowledgeMapper.selectList(
            Wrappers.lambdaQuery(KnowledgeDO.class).eq(KnowledgeDO::getDelFlag, 0)
        );
        return searchCollectionsParallel(allKnowledge, vector, topK);
    }

    /**
     * 并行在多个 Milvus Collection 中执行向量检索，合并所有结果。
     * 单个 Collection 检索失败不影响其他 Collection。
     */
    private List<RetrievalMatch> searchCollectionsParallel(List<KnowledgeDO> knowledgeList,
                                                           List<Float> vector,
                                                           int topK) {
        if (knowledgeList == null || knowledgeList.isEmpty() || vector == null) {
            return Collections.emptyList();
        }

        List<CompletableFuture<List<RetrievalMatch>>> futures = knowledgeList.stream()
            .map(kb -> CompletableFuture.supplyAsync(() -> {
                try {
                    return milvusRetrieverService.search(KnowledgeNameConverter.toCollectionName(kb.getName()), vector, topK);
                } catch (Exception e) {
                    log.warn("向量检索失败: collection={}, error={}", kb.getName(), e.getMessage());
                    return Collections.<RetrievalMatch>emptyList();
                }
            }, retrievalExecutor))
            .collect(Collectors.toList());

        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .get(PARALLEL_SEARCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("并行向量检索超时({}s), 使用已完成的部分结果", PARALLEL_SEARCH_TIMEOUT_SECONDS);
            // 取消仍在运行的任务，释放线程池资源
            for (CompletableFuture<List<RetrievalMatch>> f : futures) {
                if (!f.isDone()) {
                    f.cancel(true);
                }
            }
        } catch (Exception e) {
            log.warn("并行向量检索异常: {}", e.getMessage());
        }

        return futures.stream()
            .filter(f -> f.isDone() && !f.isCancelled() && !f.isCompletedExceptionally())
            .map(f -> f.getNow(Collections.<RetrievalMatch>emptyList()))
            .flatMap(List::stream)
            .collect(Collectors.toList());
    }

    private int calculateRecallSize(String query, int topK) {
        int factor = isShortQuery(query) ? 50 : 30;
        int recall = topK * factor;
        return Math.min(recall, MAX_RECALL_SIZE);
    }

    private boolean isShortQuery(String query) {
        if (query == null) {
            return true;
        }
        return query.trim().length() <= 6;
    }

    /**
     * 纯文本检索（备用方案）
     */
    private List<RetrievalMatch> performTextOnlyRetrieval(String query,
                                                          int topK,
                                                          String userId)
            throws Exception {
        List<RetrievalMatch> results = performTextOnlyRetrievalRaw(query, topK);
        return filterAndEnrichMatches(results, userId, topK);
    }

    private List<RetrievalMatch> performTextOnlyRetrievalRaw(String query,
                                                             int topK)
            throws Exception {
        try {
            SearchResponse<ESIndexDO> response = esClient.search(searchBuilder ->
                            searchBuilder
                                    .index(esProperties.getIndex())
                                    .query(queryBuilder -> queryBuilder
                                            .match(matchBuilder -> matchBuilder
                                                    .field("text_data")
                                                    .query(query)
                                                    .operator(isShortQuery(query) ? Operator.Or : Operator.And)
                                            )
                                    )
                                    .size(topK),
                    ESIndexDO.class
            );

            Set<Long> documentIds = response.hits().hits().stream()
                    .filter(hit -> hit.source() != null && hit.source().getDocumentId() != null)
                    .map(hit -> hit.source().getDocumentId())
                    .collect(Collectors.toSet());
            Map<Long, String> md5Map = loadMd5MapByDocumentIds(documentIds);

            List<RetrievalMatch> results = response.hits().hits().stream()
                    .filter(hit -> hit.source() != null)
                    .map(hit -> new RetrievalMatch(
                            md5Map.get(hit.source().getDocumentId()),
                            hit.source().getFragmentIndex(),
                            hit.source().getTextData(),
                            hit.score()
                    ))
                    .filter(match -> match.getFileMd5() != null && !match.getFileMd5().isBlank())
                    .collect(Collectors.toList());
            return results;
        } catch (Exception e) {
            if (isIndexMissing(e)) {
                log.warn("索引 {} 不存在，文本检索返回空结果", esProperties.getIndex());
                return Collections.emptyList();
            }
            throw e;
        }
    }

    private List<RetrievalMatch> performVectorOnlyRetrievalRaw(String queryText,
                                                               List<Float> vector,
                                                               int topK) throws Exception {
        int recallSize = calculateRecallSize(queryText, topK);
        return searchAllCollections(vector, recallSize);
    }

    /**
     * 生成查询向量
     */
    private List<Float> generateQueryVector(String text) {
        try {
            List<float[]> vectors = encodingService.encode(List.of(text));
            if (vectors == null || vectors.isEmpty()) {
                log.warn("向量编码返回空结果");
                return null;
            }

            float[] vectorArray = vectors.get(0);
            List<Float> vectorList = new ArrayList<>(vectorArray.length);
            for (float value : vectorArray) {
                vectorList.add(value);
            }
            return vectorList;
        } catch (Exception e) {
            log.error("向量生成失败", e);
            return null;
        }
    }

    /**
     * 权限过滤
     */
    private List<RetrievalMatch> filterMatchesByAccess(List<RetrievalMatch> matches,
                                                       String userId,
                                                       int topK) {
        if (matches == null || matches.isEmpty()) {
            return Collections.emptyList();
        }

        String normalizedUserId = (userId == null || userId.isBlank()) ? "anonymous" : userId;
        Set<String> allowedMd5 = new HashSet<>(loadMd5ByOwnerId(normalizedUserId));

        List<RetrievalMatch> filtered = matches.stream()
                .filter(match -> allowedMd5.contains(match.getFileMd5()))
                .collect(Collectors.toList());

        if (filtered.size() > topK) {
            return filtered.subList(0, topK);
        }

        return filtered;
    }

    private List<RetrievalMatch> filterAndEnrichMatches(List<RetrievalMatch> matches,
                                                        String userId,
                                                        int topK) {
        if (matches == null || matches.isEmpty()) {
            return Collections.emptyList();
        }

        List<RetrievalMatch> accessFiltered = filterMatchesByAccess(matches, userId, matches.size());
        if (accessFiltered.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, DocumentDO> recordMap = loadDocumentRecords(accessFiltered);
        List<RetrievalMatch> filtered = new ArrayList<>();

        for (RetrievalMatch match : accessFiltered) {
            DocumentDO record = recordMap.get(match.getFileMd5());
            if (record == null) {
                continue;
            }
            match.setSourceFileName(record.getOriginalFileName());
            filtered.add(match);
        }

        if (filtered.size() > topK) {
            return filtered.subList(0, topK);
        }
        return filtered;
    }

    private Map<String, DocumentDO> loadDocumentRecords(List<RetrievalMatch> matches) {
        Set<String> md5Set = matches.stream()
                .map(RetrievalMatch::getFileMd5)
                .collect(Collectors.toSet());
        if (md5Set.isEmpty()) {
            return Map.of();
        }
        LambdaQueryWrapper<DocumentDO> queryWrapper = Wrappers.lambdaQuery(DocumentDO.class)
                .eq(DocumentDO::getDelFlag, 0)
                .in(DocumentDO::getMd5Hash, md5Set);
        List<DocumentDO> documentDOS = documentMapper.selectList(queryWrapper);
        if (documentDOS == null || documentDOS.isEmpty()) {
            return Map.of();
        }
        return documentDOS.stream()
                .collect(Collectors.toMap(DocumentDO::getMd5Hash, doc -> doc));
    }

    private Map<Long, String> loadMd5MapByDocumentIds(Set<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return Map.of();
        }
        LambdaQueryWrapper<DocumentDO> queryWrapper = Wrappers.lambdaQuery(DocumentDO.class)
                .in(DocumentDO::getId, documentIds)
                .select(DocumentDO::getId, DocumentDO::getMd5Hash);
        List<DocumentDO> documentDOS = documentMapper.selectList(queryWrapper);
        if (documentDOS == null || documentDOS.isEmpty()) {
            return Map.of();
        }
        return documentDOS.stream()
                .filter(doc -> doc.getId() != null && doc.getMd5Hash() != null && !doc.getMd5Hash().isBlank())
                .collect(Collectors.toMap(DocumentDO::getId, DocumentDO::getMd5Hash));
    }

    private boolean isIndexMissing(Throwable error) {
        if (error == null) {
            return false;
        }
        String message = error.getMessage();
        if (message != null) {
            String lowered = message.toLowerCase(Locale.ROOT);
            if (lowered.contains("index_not_found_exception") || lowered.contains("index_not_found")) {
                return true;
            }
        }
        return isIndexMissing(error.getCause());
    }

    private List<RetrievalMatch> fuseRetrievalMatches(List<List<RetrievalMatch>> resultLists, int topK) {
        if (resultLists == null || resultLists.isEmpty()) {
            return Collections.emptyList();
        }
        int rrfK = Math.max(1, ragProperties.getFusion().getRrfK());
        Map<String, RetrievalMatch> fused = new LinkedHashMap<>();
        Map<String, Double> scores = new HashMap<>();

        for (List<RetrievalMatch> resultList : resultLists) {
            if (resultList == null || resultList.isEmpty()) {
                continue;
            }
            for (int i = 0; i < resultList.size(); i++) {
                RetrievalMatch match = resultList.get(i);
                String key = match.matchKey();
                fused.computeIfAbsent(key, ignored -> new RetrievalMatch(
                    match.getFileMd5(),
                    match.getChunkId(),
                    match.getTextContent(),
                    0.0
                ));
                scores.merge(key, 1.0 / (rrfK + i + 1), Double::sum);
            }
        }

        return fused.entrySet().stream()
            .peek(entry -> entry.getValue().setRelevanceScore(scores.getOrDefault(entry.getKey(), 0.0)))
            .map(Map.Entry::getValue)
            .sorted((left, right) -> Double.compare(
                right.getRelevanceScore() != null ? right.getRelevanceScore() : 0.0,
                left.getRelevanceScore() != null ? left.getRelevanceScore() : 0.0
            ))
            .limit(topK)
            .collect(Collectors.toList());
    }

    private List<String> loadMd5ByOwnerId(String userId) {
        Long ownerId = parseUserId(userId);
        if (ownerId == null) {
            return List.of();
        }
        LambdaQueryWrapper<DocumentDO> queryWrapper = Wrappers.lambdaQuery(DocumentDO.class)
                .eq(DocumentDO::getUserId, ownerId)
                .select(DocumentDO::getMd5Hash);
        List<DocumentDO> rows = documentMapper.selectList(queryWrapper);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        return rows.stream()
                .map(DocumentDO::getMd5Hash)
                .filter(md5 -> md5 != null && !md5.isBlank())
                .toList();
    }

    private Long parseUserId(String userId) {
        try {
            return userId == null ? null : Long.valueOf(userId);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
