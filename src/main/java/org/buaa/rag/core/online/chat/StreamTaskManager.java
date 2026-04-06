package org.buaa.rag.core.online.chat;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 流式任务生命周期管理器：维护 taskId → 取消句柄 的映射，
 * 支持 SSE 断连时主动清理资源。
 *
 * <p>相比旧版增强：
 * <ul>
 *   <li>{@link #isCancelled(String)} - 允许业务层在生成过程中轮询是否已取消</li>
 *   <li>取消时原子执行：调用取消句柄 + 标记 cancelled 状态</li>
 *   <li>{@link #activeTasks()} - 返回当前活跃任务ID快照，便于监控</li>
 * </ul>
 */
@Component
public class StreamTaskManager {

    private static final Logger log = LoggerFactory.getLogger(StreamTaskManager.class);

    /** taskId → 取消句柄（SSE 断连时调用） */
    private final Map<String, Runnable> cancelHandlers = new ConcurrentHashMap<>();

    /** 已被标记取消的 taskId 集合（原子写入，供业务层轮询） */
    private final Set<String> cancelledIds = ConcurrentHashMap.newKeySet();

    /**
     * 绑定取消句柄：任务启动后调用，允许外部通过 taskId 触发取消逻辑。
     *
     * @param taskId        任务唯一 ID
     * @param cancelHandler 取消时执行的清理逻辑（如更新 DB 消息状态）
     */
    public void bindCancel(String taskId, Runnable cancelHandler) {
        if (taskId == null || taskId.isBlank() || cancelHandler == null) {
            return;
        }
        cancelHandlers.put(taskId, cancelHandler);
    }

    /**
     * 解绑并清理：任务正常完成后调用，防止内存泄漏。
     */
    public void unbind(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        cancelHandlers.remove(taskId);
        cancelledIds.remove(taskId);
    }

    /**
     * 触发取消：执行取消句柄并标记 cancelled 状态。
     * 幂等：重复调用无副作用。
     */
    public void cancel(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        cancelledIds.add(taskId);
        Runnable handler = cancelHandlers.remove(taskId);
        if (handler != null) {
            try {
                handler.run();
            } catch (Exception e) {
                log.warn("取消句柄执行异常, taskId={}", taskId, e);
            }
        }
    }

    /**
     * 查询任务是否已被取消（供业务层在流式生成中间轮询）。
     *
     * @param taskId 任务 ID
     * @return true 表示已取消，业务层应提前退出
     */
    public boolean isCancelled(String taskId) {
        return taskId != null && cancelledIds.contains(taskId);
    }

    /**
     * 返回当前所有活跃任务 ID 的快照（不含已取消的）。
     */
    public Set<String> activeTasks() {
        return Set.copyOf(cancelHandlers.keySet());
    }
}
