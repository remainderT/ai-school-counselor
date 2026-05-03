package org.buaa.rag.service.impl;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

import org.buaa.rag.dao.entity.RagTraceNodeDO;
import org.buaa.rag.dao.entity.RagTraceRunDO;
import org.buaa.rag.dao.mapper.RagTraceNodeMapper;
import org.buaa.rag.dao.mapper.RagTraceRunMapper;
import org.buaa.rag.dto.resp.RagTraceNodeVO;
import org.buaa.rag.dto.resp.RagTraceRunVO;
import org.buaa.rag.service.RagTraceQueryService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RagTraceQueryServiceImpl implements RagTraceQueryService {

    private static final DateTimeFormatter FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final RagTraceRunMapper  runMapper;
    private final RagTraceNodeMapper nodeMapper;

    @Override
    public IPage<RagTraceRunVO> pageRuns(int current, int size,
                                          String traceId, String conversationId,
                                          String taskId, String status) {
        int safeCurrent = Math.max(1, current);
        int safeSize    = Math.min(Math.max(1, size), 100);

        LambdaQueryWrapper<RagTraceRunDO> wrapper = Wrappers.lambdaQuery(RagTraceRunDO.class)
            .orderByDesc(RagTraceRunDO::getStartTime);

        if (StringUtils.hasText(traceId)) {
            wrapper.eq(RagTraceRunDO::getTraceId, traceId.trim());
        }
        if (StringUtils.hasText(conversationId)) {
            wrapper.eq(RagTraceRunDO::getConversationId, conversationId.trim());
        }
        if (StringUtils.hasText(taskId)) {
            wrapper.eq(RagTraceRunDO::getTaskId, taskId.trim());
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(RagTraceRunDO::getStatus, status.trim().toUpperCase());
        }

        IPage<RagTraceRunDO> page = runMapper.selectPage(new Page<>(safeCurrent, safeSize), wrapper);
        return page.convert(this::toRunVO);
    }

    @Override
    public RagTraceRunVO getRunByTraceId(String traceId) {
        if (!StringUtils.hasText(traceId)) {
            return null;
        }
        RagTraceRunDO run = runMapper.selectOne(
            Wrappers.lambdaQuery(RagTraceRunDO.class)
                .eq(RagTraceRunDO::getTraceId, traceId.trim())
                .last("LIMIT 1")
        );
        return run != null ? toRunVO(run) : null;
    }

    @Override
    public List<RagTraceNodeVO> listNodes(String traceId) {
        if (!StringUtils.hasText(traceId)) {
            return List.of();
        }
        List<RagTraceNodeDO> nodes = nodeMapper.selectList(
            Wrappers.lambdaQuery(RagTraceNodeDO.class)
                .eq(RagTraceNodeDO::getTraceId, traceId.trim())
                .orderByAsc(RagTraceNodeDO::getStartTime)
                .orderByAsc(RagTraceNodeDO::getId)
        );
        if (nodes == null) {
            return List.of();
        }
        return nodes.stream().map(this::toNodeVO).collect(Collectors.toList());
    }

    // ── 转换工具 ──────────────────────────────────────────────────────────────

    private RagTraceRunVO toRunVO(RagTraceRunDO d) {
        RagTraceRunVO vo = new RagTraceRunVO();
        vo.setTraceId(d.getTraceId());
        vo.setTraceName(d.getTraceName());
        vo.setConversationId(d.getConversationId());
        vo.setTaskId(d.getTaskId());
        vo.setUserId(d.getUserId());
        vo.setStatus(d.getStatus());
        vo.setErrorMessage(d.getErrorMessage());
        vo.setDurationMs(d.getDurationMs());
        vo.setStartTime(formatDate(d.getStartTime()));
        vo.setEndTime(formatDate(d.getEndTime()));
        return vo;
    }

    private RagTraceNodeVO toNodeVO(RagTraceNodeDO d) {
        RagTraceNodeVO vo = new RagTraceNodeVO();
        vo.setTraceId(d.getTraceId());
        vo.setNodeId(d.getNodeId());
        vo.setParentNodeId(d.getParentNodeId());
        vo.setDepth(d.getDepth());
        vo.setNodeType(d.getNodeType());
        vo.setNodeName(d.getNodeName());
        vo.setClassName(d.getClassName());
        vo.setMethodName(d.getMethodName());
        vo.setStatus(d.getStatus());
        vo.setErrorMessage(d.getErrorMessage());
        vo.setDurationMs(d.getDurationMs());
        vo.setStartTime(formatDate(d.getStartTime()));
        vo.setEndTime(formatDate(d.getEndTime()));
        return vo;
    }

    private String formatDate(java.util.Date date) {
        if (date == null) {
            return null;
        }
        return date.toInstant()
            .atZone(ZoneId.of("Asia/Shanghai"))
            .toLocalDateTime()
            .format(FMT);
    }
}
