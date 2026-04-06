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
import org.buaa.rag.dao.entity.DocumentDO;
import org.buaa.rag.dao.entity.KnowledgeDO;
import org.buaa.rag.dao.mapper.DocumentMapper;
import org.buaa.rag.dao.mapper.KnowledgeMapper;
import org.buaa.rag.dto.req.KnowledgeCreateReqDTO;
import org.buaa.rag.dto.req.KnowledgeUpdateReqDTO;
import org.buaa.rag.dto.resp.KnowledgeListRespDTO;
import org.buaa.rag.service.KnowledgeService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class KnowledgeServiceImpl extends ServiceImpl<KnowledgeMapper, KnowledgeDO> implements KnowledgeService {

    private final DocumentMapper documentMapper;

    @Override
    public Long create(KnowledgeCreateReqDTO requestParam) {
        String name = normalizeStr(requestParam == null ? null : requestParam.getName());
        if (name == null) {
            throw new ClientException(KNOWLEDGE_NAME_EMPTY);
        }
        Long userId = requireCurrentUserId();

        ensureNameNotDuplicate(userId, name, null);
        KnowledgeDO knowledge = KnowledgeDO.builder()
            .userId(userId)
            .name(name)
            .description(normalizeStr(requestParam == null ? null : requestParam.getDescription()))
            .build();
        try {
            baseMapper.insert(knowledge);
        } catch (DuplicateKeyException ex) {
            throw new ClientException(KNOWLEDGE_NAME_DUPLICATE);
        }
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

        // 批量查询每个知识库的文档数量
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

        String nextName = normalizeStr(requestParam == null ? null : requestParam.getName());
        if (nextName != null && !nextName.equals(existing.getName())) {
            ensureNameNotDuplicate(existing.getUserId(), nextName, existing.getId());
            existing.setName(nextName);
        }

        if (requestParam != null) {
            if (requestParam.getDescription() != null) {
                existing.setDescription(normalizeStr(requestParam.getDescription()));
            }
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

    private String normalizeStr(String str) {
        if (!StringUtils.hasText(str)) {
            return null;
        }
        return str.trim();
    }
}
