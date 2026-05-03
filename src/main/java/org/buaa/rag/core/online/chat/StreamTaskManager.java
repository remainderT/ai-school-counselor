package org.buaa.rag.core.online.chat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 流式任务生命周期管理器。
 *
 * <p>维护 taskId → 取消句柄 的映射关系，支持 SSE 断连时主动清理资源，
 * 并提供业务层轮询式取消检测（{@link #isCancelled(String)}）。
 *
 * <p>增强特性：
 * <ul>
 *   <li>自动过期清理——后台定时扫描并移除超过 TTL 的残留条目，防止内存泄漏</li>
 *   <li>容量保护——活跃任务数超过上限时拒绝新注册并记录告警</li>
 *   <li>幂等取消——重复调用 {@link #cancel(String)} 无副作用</li>
 * </ul>
 */
@Component
public class StreamTaskManager {

    private static final Logger log = LoggerFactory.getLogger(StreamTaskManager.class);

    /** 单条任务最大存活时间（超过后自动清理） */
    private static final Duration ENTRY_TTL = Duration.ofMinutes(10);

    /** 允许同时存在的最大活跃任务数 */
    private static final int MAX_ACTIVE_TASKS = 500;

    /** 清理扫描间隔 */
    private static final long CLEANUP_INTERVAL_SECONDS = 60;

    /** 任务条目：封装取消句柄和注册时间 */
    private record TaskEntry(Runnable cancelHandler, Instant registeredAt) {}

    /** taskId → 任务条目 */
    private final Map<String, TaskEntry> entries = new ConcurrentHashMap<>();

    /** 已被标记取消的 taskId 集合 */
    private final Set<String> cancelledIds = ConcurrentHashMap.newKeySet();

    /** 后台清理线程 */
    private final ScheduledExecutorService cleanupScheduler;

    public StreamTaskManager() {
        this.cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "stream-task-cleanup");
            t.setDaemon(true);
            return t;
        });
        cleanupScheduler.scheduleAtFixedRate(
                this::evictExpiredEntries,
                CLEANUP_INTERVAL_SECONDS, CLEANUP_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 注册取消句柄。若超出容量上限则拒绝注册并返回 {@code false}。
     *
     * @param taskId        任务唯一 ID
     * @param cancelHandler 取消时执行的清理逻辑
     * @return {@code true} 表示注册成功
     */
    public boolean bindCancel(String taskId, Runnable cancelHandler) {
        if (taskId == null || taskId.isBlank() || cancelHandler == null) {
            return false;
        }
        if (entries.size() >= MAX_ACTIVE_TASKS) {
            log.warn("流式任务数达到上限({}), 拒绝注册: taskId={}", MAX_ACTIVE_TASKS, taskId);
            return false;
        }
        entries.put(taskId, new TaskEntry(cancelHandler, Instant.now()));
        return true;
    }

    /**
     * 解绑并清理：任务正常完成后调用，释放资源。
     */
    public void unbind(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        entries.remove(taskId);
        cancelledIds.remove(taskId);
    }

    /**
     * 触发取消并标记状态。幂等操作。
     */
    public void cancel(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        cancelledIds.add(taskId);
        TaskEntry entry = entries.remove(taskId);
        if (entry != null && entry.cancelHandler() != null) {
            try {
                entry.cancelHandler().run();
            } catch (Exception e) {
                log.warn("取消句柄执行异常, taskId={}", taskId, e);
            }
        }
    }

    /**
     * 查询任务是否已被取消。
     */
    public boolean isCancelled(String taskId) {
        return taskId != null && cancelledIds.contains(taskId);
    }

    /**
     * 返回当前活跃任务 ID 快照。
     */
    public Set<String> activeTasks() {
        return Set.copyOf(entries.keySet());
    }

    /**
     * 当前活跃任务数量。
     */
    public int activeCount() {
        return entries.size();
    }

    @PreDestroy
    void shutdown() {
        cleanupScheduler.shutdownNow();
    }

    // ────────────────── 后台清理 ──────────────────

    private void evictExpiredEntries() {
        try {
            Instant cutoff = Instant.now().minus(ENTRY_TTL);
            int evicted = 0;
            var iterator = entries.entrySet().iterator();
            while (iterator.hasNext()) {
                var e = iterator.next();
                if (e.getValue().registeredAt().isBefore(cutoff)) {
                    iterator.remove();
                    cancelledIds.remove(e.getKey());
                    evicted++;
                }
            }
            if (evicted > 0) {
                log.info("流式任务过期清理: 移除 {} 条残留条目, 剩余活跃 {}", evicted, entries.size());
            }
        } catch (Exception e) {
            log.warn("流式任务过期清理异常", e);
        }
    }
}
