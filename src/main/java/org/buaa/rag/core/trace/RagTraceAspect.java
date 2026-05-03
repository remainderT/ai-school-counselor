package org.buaa.rag.core.trace;

import java.lang.reflect.Method;
import java.util.Date;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.buaa.rag.dao.entity.RagTraceNodeDO;
import org.buaa.rag.dao.entity.RagTraceRunDO;
import org.buaa.rag.dao.mapper.RagTraceNodeMapper;
import org.buaa.rag.dao.mapper.RagTraceRunMapper;
import org.buaa.rag.properties.RagTraceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import lombok.RequiredArgsConstructor;

/**
 * RAG 全链路 Trace AOP 切面。
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class RagTraceAspect {

    private static final Logger log = LoggerFactory.getLogger(RagTraceAspect.class);

    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_ERROR   = "ERROR";

    private final RagTraceRunMapper  runMapper;
    private final RagTraceNodeMapper nodeMapper;
    private final RagTraceProperties traceProperties;

    // ─────────────────────────────────────────────────────────────────────────
    // @RagTraceRoot 处理：创建链路主记录
    // ─────────────────────────────────────────────────────────────────────────

    @Around("@annotation(traceRoot)")
    public Object aroundRoot(ProceedingJoinPoint joinPoint, RagTraceRoot traceRoot) throws Throwable {
        if (!traceProperties.isEnabled()) {
            return joinPoint.proceed();
        }
        // 防止嵌套：同一线程已有 traceId 则不重复创建 Run 记录
        if (RagTraceContext.hasActiveTrace()) {
            return joinPoint.proceed();
        }

        MethodSignature signature  = (MethodSignature) joinPoint.getSignature();
        Method          method     = signature.getMethod();
        String          traceId    = generateId();
        String          conversationId = resolveStringArg(signature, joinPoint.getArgs(), traceRoot.conversationIdArg());
        String          taskId         = resolveStringArg(signature, joinPoint.getArgs(), traceRoot.taskIdArg());
        String          traceName      = StringUtils.hasText(traceRoot.name()) ? traceRoot.name() : method.getName();
        String          entryMethod    = method.getDeclaringClass().getSimpleName() + "#" + method.getName();
        Date            startTime      = new Date();
        long            startMillis    = System.currentTimeMillis();

        // 写入 RUNNING 状态的 Run 记录（忽略写入失败，不影响正常业务）
        safeInsertRun(RagTraceRunDO.builder()
            .traceId(traceId)
            .traceName(traceName)
            .entryMethod(entryMethod)
            .conversationId(conversationId)
            .taskId(taskId)
            .status(STATUS_RUNNING)
            .startTime(startTime)
            .build());

        RagTraceContext.setTraceId(traceId);
        if (StringUtils.hasText(taskId)) {
            RagTraceContext.setTaskId(taskId);
        }

        try {
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - startMillis;
            safeFinishRun(traceId, STATUS_SUCCESS, null, new Date(), duration);
            return result;
        } catch (Throwable ex) {
            long duration = System.currentTimeMillis() - startMillis;
            safeFinishRun(traceId, STATUS_ERROR, truncateError(ex), new Date(), duration);
            throw ex;
        } finally {
            RagTraceContext.clear();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // @RagTraceNode 处理：创建节点记录（维护父子树）
    // ─────────────────────────────────────────────────────────────────────────

    @Around("@annotation(traceNode)")
    public Object aroundNode(ProceedingJoinPoint joinPoint, RagTraceNode traceNode) throws Throwable {
        if (!traceProperties.isEnabled()) {
            return joinPoint.proceed();
        }
        // 不在任何 Trace 链路内则跳过
        if (!RagTraceContext.hasActiveTrace()) {
            return joinPoint.proceed();
        }

        String traceId      = RagTraceContext.getTraceId();
        String nodeId       = generateId();
        String parentNodeId = RagTraceContext.currentNodeId();
        int    depth        = RagTraceContext.depth();

        MethodSignature signature  = (MethodSignature) joinPoint.getSignature();
        Method          method     = signature.getMethod();
        String          nodeName   = StringUtils.hasText(traceNode.name()) ? traceNode.name() : method.getName();
        String          nodeType   = StringUtils.hasText(traceNode.type()) ? traceNode.type() : "METHOD";
        Date            startTime  = new Date();
        long            startMillis = System.currentTimeMillis();

        safeInsertNode(RagTraceNodeDO.builder()
            .traceId(traceId)
            .nodeId(nodeId)
            .parentNodeId(parentNodeId)
            .depth(depth)
            .nodeType(nodeType)
            .nodeName(nodeName)
            .className(method.getDeclaringClass().getSimpleName())
            .methodName(method.getName())
            .status(STATUS_RUNNING)
            .startTime(startTime)
            .build());

        RagTraceContext.pushNode(nodeId);
        try {
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - startMillis;
            safeFinishNode(traceId, nodeId, STATUS_SUCCESS, null, new Date(), duration);
            return result;
        } catch (Throwable ex) {
            long duration = System.currentTimeMillis() - startMillis;
            safeFinishNode(traceId, nodeId, STATUS_ERROR, truncateError(ex), new Date(), duration);
            throw ex;
        } finally {
            RagTraceContext.popNode();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 内部工具
    // ─────────────────────────────────────────────────────────────────────────

    private void safeInsertRun(RagTraceRunDO run) {
        try {
            runMapper.insert(run);
        } catch (Exception e) {
            log.debug("Trace Run 写入失败（忽略）: traceId={}, error={}", run.getTraceId(), e.getMessage());
        }
    }

    private void safeFinishRun(String traceId, String status, String error, Date endTime, long duration) {
        try {
            RagTraceRunDO update = new RagTraceRunDO();
            update.setStatus(status);
            update.setErrorMessage(error);
            update.setEndTime(endTime);
            update.setDurationMs(duration);
            runMapper.update(update, Wrappers.lambdaUpdate(RagTraceRunDO.class)
                .eq(RagTraceRunDO::getTraceId, traceId));
        } catch (Exception e) {
            log.debug("Trace Run 更新失败（忽略）: traceId={}, error={}", traceId, e.getMessage());
        }
    }

    private void safeInsertNode(RagTraceNodeDO node) {
        try {
            nodeMapper.insert(node);
        } catch (Exception e) {
            log.debug("Trace Node 写入失败（忽略）: traceId={}, nodeId={}, error={}",
                node.getTraceId(), node.getNodeId(), e.getMessage());
        }
    }

    private void safeFinishNode(String traceId, String nodeId, String status,
                                 String error, Date endTime, long duration) {
        try {
            RagTraceNodeDO update = new RagTraceNodeDO();
            update.setStatus(status);
            update.setErrorMessage(error);
            update.setEndTime(endTime);
            update.setDurationMs(duration);
            nodeMapper.update(update, Wrappers.lambdaUpdate(RagTraceNodeDO.class)
                .eq(RagTraceNodeDO::getTraceId, traceId)
                .eq(RagTraceNodeDO::getNodeId, nodeId));
        } catch (Exception e) {
            log.debug("Trace Node 更新失败（忽略）: traceId={}, nodeId={}", traceId, nodeId);
        }
    }

    /** 按参数名从方法签名中提取字符串参数值 */
    private String resolveStringArg(MethodSignature signature, Object[] args, String argName) {
        if (!StringUtils.hasText(argName) || args == null || args.length == 0) {
            return null;
        }
        String[] paramNames = signature.getParameterNames();
        if (paramNames == null) {
            return null;
        }
        for (int i = 0; i < paramNames.length; i++) {
            if (argName.equals(paramNames[i]) && i < args.length && args[i] instanceof String) {
                return (String) args[i];
            }
        }
        return null;
    }

    /** 生成全局唯一 ID（基于 UUID，避免高并发下时间戳 + 随机数碰撞） */
    private String generateId() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }

    /** 截断异常信息，防止超 VARCHAR 长度限制 */
    private String truncateError(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        String msg = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
        int maxLen = traceProperties.getMaxErrorLength();
        if (msg.length() <= maxLen) {
            return msg;
        }
        return msg.substring(0, maxLen - 3) + "...";
    }
}
