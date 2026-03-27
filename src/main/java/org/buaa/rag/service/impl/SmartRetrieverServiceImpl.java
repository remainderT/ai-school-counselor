package org.buaa.rag.service.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.buaa.rag.properties.EsProperties;
import org.buaa.rag.properties.RagProperties;
import org.buaa.rag.dao.entity.DocumentDO;
import org.buaa.rag.dao.entity.ESIndexDO;
import org.buaa.rag.dao.entity.MessageFeedbackDO;
import org.buaa.rag.dao.entity.MessageSourceDO;
import org.buaa.rag.dao.mapper.DocumentMapper;
import org.buaa.rag.dao.mapper.MessageFeedbackMapper;
import org.buaa.rag.dao.mapper.MessageSourceMapper;
import org.buaa.rag.dto.RetrievalMatch;
import org.buaa.rag.module.index.MilvusRetrieverService;
import org.buaa.rag.service.SmartRetrieverService;
import org.buaa.rag.module.index.VectorEncoding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import lombok.RequiredArgsConstructor;

/**
 * 智能检索服务实现
 * 结合向量检索和文本匹配的混合搜索策略
 */
@Service
@RequiredArgsConstructor
public class SmartRetrieverServiceImpl implements SmartRetrieverService {

    private static final Logger log = LoggerFactory.getLogger(SmartRetrieverServiceImpl.class);
    private static final int MAX_RECALL_SIZE = 300;

    private final ElasticsearchClient esClient;
    private final VectorEncoding encodingService;
    private final DocumentMapper documentMapper;
    private final RagProperties ragProperties;
    private final MessageFeedbackMapper feedbackRepository;
    private final MessageSourceMapper sourceRepository;
    private final EsProperties esProperties;
    private final MilvusRetrieverService milvusRetrieverService;

    @Override
    public List<RetrievalMatch> retrieve(String queryText, int topK) {
        return retrieve(queryText, topK, null);
    }

    @Override
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
            throw new RuntimeException("检索过程发生异常");
        }
    }

    @Override
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

    @Override
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
            log.error("文本检索失败", e);
            return Collections.emptyList();
        }
    }

    @Override
    public void recordFeedback(Long messageId, String userId, int score, String comment) {
        MessageFeedbackDO feedback = new MessageFeedbackDO();
        feedback.setMessageId(messageId);
        feedback.setUserId(parseUserId(userId));
        feedback.setScore(score);
        feedback.setComment(comment);
        feedbackRepository.insert(feedback);
    }

    private Long parseUserId(String userId) {
        try {
            return userId == null ? null : Long.valueOf(userId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 执行混合检索
     */
    private List<RetrievalMatch> performHybridRetrieval(String query,
                                                        List<Float> vector,
                                                        int topK) throws Exception {
        int recallSize = calculateRecallSize(query, topK);
        List<RetrievalMatch> textMatches = performTextOnlyRetrievalRaw(query, recallSize);
        List<RetrievalMatch> vectorMatches = milvusRetrieverService.search(vector, recallSize);
        return fuseRetrievalMatches(List.of(textMatches, vectorMatches), topK);
    }

    private int calculateRecallSize(String query, int topK) {
        int factor = isShortQuery(query) ? 50 : 30;
        int recall = topK * factor;
        return Math.min(recall, MAX_RECALL_SIZE);
    }

    private Operator resolveOperator(String query) {
        return isShortQuery(query) ? Operator.Or : Operator.And;
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
                                                    .operator(resolveOperator(query))
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
        return milvusRetrieverService.search(vector, recallSize);
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

        String normalizedUserId = normalizeUserId(userId);
        Set<String> allowedMd5 = resolveAccessibleDocuments(normalizedUserId);

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

        applyFeedbackBoost(filtered);

        if (filtered.size() > topK) {
            return filtered.subList(0, topK);
        }
        return filtered;
    }

    /**
     * 获取用户可访问的文档 MD5 列表。
     *
     * <p>仅允许用户本人上传的文档。</p>
     *
     * @param userId 用户标识
     * @return 可访问文档的 MD5 集合
     */
    private Set<String> resolveAccessibleDocuments(String userId) {
        Set<String> md5Set = new HashSet<>();
        md5Set.addAll(loadMd5ByOwnerId(userId));

        return md5Set;
    }

    private String normalizeUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return "anonymous";
        }
        return userId;
    }

    private Map<String, DocumentDO> loadDocumentRecords(List<RetrievalMatch> matches) {
        Set<String> md5Set = matches.stream()
                .map(RetrievalMatch::getFileMd5)
                .collect(Collectors.toSet());
        if (md5Set.isEmpty()) {
            return Map.of();
        }
        LambdaQueryWrapper<DocumentDO> queryWrapper = Wrappers.lambdaQuery(DocumentDO.class)
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

    private void applyFeedbackBoost(List<RetrievalMatch> matches) {
        if (matches == null || matches.isEmpty()) {
            return;
        }
        RagProperties.Feedback config = ragProperties.getFeedback();
        if (config == null || !config.isEnabled()) {
            return;
        }

        Set<String> md5Set = matches.stream()
                .map(RetrievalMatch::getFileMd5)
                .collect(Collectors.toSet());
        Map<String, Double> boostMap = loadBoostMap(md5Set, config.getMaxBoost());
        if (boostMap.isEmpty()) {
            return;
        }

        for (RetrievalMatch match : matches) {
            double baseScore = match.getRelevanceScore() != null ? match.getRelevanceScore() : 0.0;
            double boost = boostMap.getOrDefault(match.getFileMd5(), 0.0);
            match.setRelevanceScore(baseScore * (1 + boost));
        }

        matches.sort((a, b) -> Double.compare(
                b.getRelevanceScore() != null ? b.getRelevanceScore() : 0.0,
                a.getRelevanceScore() != null ? a.getRelevanceScore() : 0.0
        ));
    }

    private Map<String, Double> loadBoostMap(Set<String> md5Set, double maxBoost) {
        if (md5Set == null || md5Set.isEmpty()) {
            return Map.of();
        }

        LambdaQueryWrapper<MessageSourceDO> sourceQuery = Wrappers.lambdaQuery(MessageSourceDO.class)
                .in(MessageSourceDO::getDocumentMd5, md5Set)
                .select(MessageSourceDO::getMessageId, MessageSourceDO::getDocumentMd5);
        List<MessageSourceDO> sourceRows = sourceRepository.selectList(sourceQuery);
        if (sourceRows == null || sourceRows.isEmpty()) {
            return Map.of();
        }

        Map<Long, List<String>> messageToDocuments = new HashMap<>();
        Set<Long> messageIds = new HashSet<>();
        for (MessageSourceDO sourceRow : sourceRows) {
            if (sourceRow == null || sourceRow.getMessageId() == null || sourceRow.getDocumentMd5() == null) {
                continue;
            }
            Long messageId = sourceRow.getMessageId();
            messageIds.add(messageId);
            messageToDocuments
                    .computeIfAbsent(messageId, key -> new ArrayList<>())
                    .add(sourceRow.getDocumentMd5());
        }
        if (messageIds.isEmpty()) {
            return Map.of();
        }

        LambdaQueryWrapper<MessageFeedbackDO> feedbackQuery = Wrappers.lambdaQuery(MessageFeedbackDO.class)
                .in(MessageFeedbackDO::getMessageId, messageIds)
                .select(MessageFeedbackDO::getMessageId, MessageFeedbackDO::getScore);
        List<MessageFeedbackDO> feedbackRows = feedbackRepository.selectList(feedbackQuery);
        if (feedbackRows == null || feedbackRows.isEmpty()) {
            return Map.of();
        }

        Map<String, double[]> scoreAccumulator = new HashMap<>();
        for (MessageFeedbackDO feedbackRow : feedbackRows) {
            if (feedbackRow == null || feedbackRow.getMessageId() == null || feedbackRow.getScore() == null) {
                continue;
            }
            List<String> documents = messageToDocuments.get(feedbackRow.getMessageId());
            if (documents == null || documents.isEmpty()) {
                continue;
            }
            for (String documentMd5 : documents) {
                double[] stats = scoreAccumulator.computeIfAbsent(documentMd5, key -> new double[2]);
                stats[0] += feedbackRow.getScore();
                stats[1] += 1;
            }
        }

        Map<String, Double> boostMap = new HashMap<>();
        for (Map.Entry<String, double[]> entry : scoreAccumulator.entrySet()) {
            String md5 = entry.getKey();
            double[] stats = entry.getValue();
            if (md5 == null || stats == null || stats[1] <= 0) {
                continue;
            }
            double avgScore = stats[0] / stats[1];
            double centered = (avgScore - 3.0) / 2.0;
            double boost = Math.max(-maxBoost, Math.min(maxBoost, centered * maxBoost));
            boostMap.put(md5, boost);
        }
        return boostMap;
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
                String key = buildMatchKey(match);
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

    private String buildMatchKey(RetrievalMatch match) {
        return match.getFileMd5() + "#" + (match.getChunkId() == null ? "null" : match.getChunkId());
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
}
