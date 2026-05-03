package org.buaa.rag.service.impl;

import static org.buaa.rag.common.enums.OfflineErrorCodeEnum.KNOWLEDGE_ACCESS_DENIED;
import static org.buaa.rag.common.enums.OfflineErrorCodeEnum.KNOWLEDGE_HAS_DOCUMENTS;
import static org.buaa.rag.common.enums.OfflineErrorCodeEnum.KNOWLEDGE_NAME_DUPLICATE;
import static org.buaa.rag.common.enums.OfflineErrorCodeEnum.KNOWLEDGE_NAME_EMPTY;
import static org.buaa.rag.common.enums.OfflineErrorCodeEnum.KNOWLEDGE_NOT_EXISTS;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.buaa.rag.common.convention.exception.ClientException;
import org.buaa.rag.common.user.UserContext;
import org.buaa.rag.core.offline.index.MilvusCollectionManager;
import org.buaa.rag.dao.entity.DocumentDO;
import org.buaa.rag.dao.entity.KnowledgeDO;
import org.buaa.rag.dao.mapper.DocumentMapper;
import org.buaa.rag.dao.mapper.KnowledgeMapper;
import org.buaa.rag.dto.req.KnowledgeCreateReqDTO;
import org.buaa.rag.dto.req.KnowledgeUpdateReqDTO;
import org.buaa.rag.dto.resp.KnowledgeListRespDTO;
import org.buaa.rag.service.KnowledgeService;
import org.buaa.rag.tool.BucketManager;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeServiceImpl extends ServiceImpl<KnowledgeMapper, KnowledgeDO> implements KnowledgeService {

    private final DocumentMapper documentMapper;
    private final BucketManager bucketManager;
    private final MilvusCollectionManager collectionManager;

    @Override
    public Long create(KnowledgeCreateReqDTO requestParam) {
        String rawName = requestParam == null ? null : requestParam.getName();
        if (!StringUtils.hasText(rawName)) {
            throw new ClientException(KNOWLEDGE_NAME_EMPTY);
        }
        String name = rawName.trim();
        Long userId = requireCurrentUserId();

        ensureNameNotDuplicate(userId, name, null);
        String desc = requestParam.getDescription();

        // RustFS Bucket 名：下划线转连字符（RustFS 不允许下划线）
        // Milvus Collection 名：直接用 knowledge.name（Milvus 不允许连字符）
        String bucketName = BucketManager.toBucketName(name);

        KnowledgeDO knowledge = KnowledgeDO.builder()
            .userId(userId)
            .name(name)
            .description(StringUtils.hasText(desc) ? desc.trim() : null)
            .build();
        try {
            baseMapper.insert(knowledge);
        } catch (DuplicateKeyException ex) {
            throw new ClientException(KNOWLEDGE_NAME_DUPLICATE);
        }

        // 同步创建对应的 RustFS Bucket 和 Milvus Collection
        try {
            bucketManager.ensureBucket(bucketName);
            collectionManager.ensureCollection(name);  // Milvus 用原始 name（下划线）
        } catch (Exception e) {
            // 存储资源创建失败时回滚数据库记录，保持一致性
            baseMapper.deleteById(knowledge.getId());
            log.error("知识库存储资源初始化失败，已回滚: name={}", name, e);
            throw new RuntimeException("知识库存储资源初始化失败: " + e.getMessage(), e);
        }

        log.info("知识库创建成功: id={}, name={}, bucketName={}", knowledge.getId(), name, bucketName);
        return knowledge.getId();
    }

    @Override
    public List<KnowledgeListRespDTO> listMine() {
        Long userId = requireCurrentUserId();
        List<KnowledgeDO> knowledgeList = baseMapper.selectList(
            Wrappers.lambdaQuery(KnowledgeDO.class)
                .eq(KnowledgeDO::getUserId, userId)
                .eq(KnowledgeDO::getDelFlag, 0)
                .orderByDesc(KnowledgeDO::getId)
        );
        if (knowledgeList.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> knowledgeIds = knowledgeList.stream()
            .map(KnowledgeDO::getId)
            .collect(Collectors.toList());
        Map<Long, Long> docCountMap = countDocumentsByKnowledgeIds(knowledgeIds);

        return knowledgeList.stream()
            .map(kb -> KnowledgeListRespDTO.builder()
                .id(kb.getId())
                .name(kb.getName())
                .description(kb.getDescription())
                .documentCount(docCountMap.getOrDefault(kb.getId(), 0L))
                .build())
            .collect(Collectors.toList());
    }

    /**
     * 批量统计各知识库下的文档数量
     */
    private Map<Long, Long> countDocumentsByKnowledgeIds(List<Long> knowledgeIds) {
        if (knowledgeIds == null || knowledgeIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<DocumentDO> documents = documentMapper.selectList(
            Wrappers.lambdaQuery(DocumentDO.class)
                .select(DocumentDO::getKnowledgeId)
                .in(DocumentDO::getKnowledgeId, knowledgeIds)
                .eq(DocumentDO::getDelFlag, 0)
        );
        return documents.stream()
            .collect(Collectors.groupingBy(DocumentDO::getKnowledgeId, Collectors.counting()));
    }

    @Override
    public KnowledgeDO detail(Long id) {
        KnowledgeDO knowledge = loadKnowledge(id);
        validateOwner(knowledge);
        return knowledge;
    }

    @Override
    public void update(Long id, KnowledgeUpdateReqDTO requestParam) {
        KnowledgeDO existing = loadKnowledge(id);
        validateOwner(existing);

        // 注意：name 更新不影响 bucket/collection（已创建的存储资源名称不可修改）
        if (requestParam != null && StringUtils.hasText(requestParam.getName())) {
            String nextName = requestParam.getName().trim();
            if (!nextName.equals(existing.getName())) {
                ensureNameNotDuplicate(existing.getUserId(), nextName, existing.getId());
                existing.setName(nextName);
            }
        }

        if (requestParam != null && requestParam.getDescription() != null) {
            String desc = requestParam.getDescription();
            existing.setDescription(StringUtils.hasText(desc) ? desc.trim() : null);
        }
        baseMapper.updateById(existing);
    }

    @Override
    public void delete(Long id) {
        KnowledgeDO knowledge = loadKnowledge(id);
        validateOwner(knowledge);

        Long relatedDocuments = documentMapper.selectCount(
            Wrappers.lambdaQuery(DocumentDO.class)
                .eq(DocumentDO::getKnowledgeId, id)
                .eq(DocumentDO::getDelFlag, 0)
        );
        if (relatedDocuments != null && relatedDocuments > 0) {
            throw new ClientException(KNOWLEDGE_HAS_DOCUMENTS);
        }

        baseMapper.deleteById(id);

        // 同步清理对应的 RustFS Bucket 和 Milvus Collection（best-effort，不阻断删除）
        String kbName = knowledge.getName();
        String bucketName = BucketManager.toBucketName(kbName);
        if (StringUtils.hasText(kbName)) {
            try {
                bucketManager.deleteBucket(bucketName);       // RustFS 用连字符
            } catch (Exception e) {
                log.warn("知识库 Bucket 清理失败（忽略）: bucketName={}", bucketName, e);
            }
            try {
                collectionManager.dropCollection(kbName);     // Milvus 用原始 name（下划线）
            } catch (Exception e) {
                log.warn("知识库 Milvus Collection 清理失败（忽略）: collection={}", kbName, e);
            }
        }

        log.info("知识库删除成功: id={}, name={}, bucketName={}", id, kbName, bucketName);
    }

    private KnowledgeDO loadKnowledge(Long id) {
        if (id == null) {
            throw new ClientException(KNOWLEDGE_NOT_EXISTS);
        }
        KnowledgeDO knowledge = baseMapper.selectOne(
            Wrappers.lambdaQuery(KnowledgeDO.class)
                .eq(KnowledgeDO::getId, id)
                .eq(KnowledgeDO::getDelFlag, 0)
        );
        if (knowledge == null) {
            throw new ClientException(KNOWLEDGE_NOT_EXISTS);
        }
        return knowledge;
    }

    private void validateOwner(KnowledgeDO knowledge) {
        Long userId = requireCurrentUserId();
        if (knowledge == null || !userId.equals(knowledge.getUserId())) {
            throw new ClientException(KNOWLEDGE_ACCESS_DENIED);
        }
    }

    private void ensureNameNotDuplicate(Long userId, String name, Long excludeId) {
        LambdaQueryWrapper<KnowledgeDO> query = Wrappers.lambdaQuery(KnowledgeDO.class)
            .eq(KnowledgeDO::getUserId, userId)
            .eq(KnowledgeDO::getName, name)
            .eq(KnowledgeDO::getDelFlag, 0);
        if (excludeId != null) {
            query.ne(KnowledgeDO::getId, excludeId);
        }
        Long count = baseMapper.selectCount(query);
        if (count != null && count > 0) {
            throw new ClientException(KNOWLEDGE_NAME_DUPLICATE);
        }
    }

    private Long requireCurrentUserId() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new ClientException(KNOWLEDGE_ACCESS_DENIED);
        }
        return userId;
    }
}
