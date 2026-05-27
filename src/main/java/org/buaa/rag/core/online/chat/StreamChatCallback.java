package org.buaa.rag.core.online.chat;

import java.util.List;

import org.buaa.rag.core.model.RetrievalMatch;

/**
 * 流式对话回调接口：封装 SSE 全生命周期事件，业务层只需调用此接口，
 * 与具体传输实现（SSE/WebSocket）完全解耦。
 *
 * <p>事件流顺序：
 * <pre>
 * onMeta()  →  onContent()*  →  onFinish()  →  onComplete()
 *                                ↘ onError()（任意阶段均可）
 * </pre>
 *
 * <p>SSE 事件名称约定：
 * <ul>
 *   <li>{@code meta}    - 首帧，携带 messageId + taskId</li>
 *   <li>{@code message} - LLM 内容分块（JSON: {"type":"response","delta":"..."}）</li>
 *   <li>{@code sources} - 检索来源列表（JSON 数组）</li>
 *   <li>{@code finish}  - 完成通知，携带会话标题（JSON: {"title":"...","messageId":123}）</li>
 *   <li>{@code done}    - 最终结束标志</li>
 *   <li>{@code error}   - 错误描述</li>
 * </ul>
 */
public interface StreamChatCallback {

    /**
     * 元信息事件：请求开始时立即发送，携带 messageId/taskId 供前端追踪。
     */
    void onMeta(Long messageId, String taskId);

    /**
     * 内容分块事件：LLM 流式输出的每个文本片段。
     */
    void onContent(String chunk);

    /**
     * 来源引用事件：流式内容结束后发送检索来源列表。
     */
    void onSources(List<RetrievalMatch> sources);

    /**
     * 完成通知事件：携带对话标题，供前端更新会话名称。
     * 在 onSources 之后、onComplete 之前发送。
     *
     * @param title     根据问题内容生成的对话标题（可为 null）
     * @param messageId 已持久化的消息 ID
     */
    void onFinish(String title, Long messageId);

    /**
     * 完成事件：一切正常结束，关闭 SSE 连接。
     */
    void onComplete();

    /**
     * 错误事件：任意阶段发生不可恢复错误时调用，关闭连接。
     */
    void onError(Throwable cause);

    /**
     * 状态提示事件：推送当前处理阶段描述给前端，供实时进度展示。
     * 例如："子问题拆解中"、"意图识别中"、"混合检索中"、"重排中"、"生成中"
     *
     * @param stage 阶段标识（英文 key），方便前端做本地化
     * @param label 中文描述，直接展示给用户
     */
    default void onStatus(String stage, String label) {
        // 默认空实现，兼容现有实现类
    }

    /**
     * 判断当前连接是否已断开（客户端主动取消或超时）。
     * 业务层可在生成过程中轮询此方法以提前退出，节省资源。
     */
    boolean isCancelled();
}
