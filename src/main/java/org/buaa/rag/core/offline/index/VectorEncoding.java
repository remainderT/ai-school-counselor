package org.buaa.rag.core.offline.index;

import java.util.List;

import org.buaa.rag.tool.DashscopeClient;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 向量编码服务
 * 通过 DashScope HTTP 接口执行向量化
 */
@Slf4j
@Component
public class VectorEncoding {

    private final DashscopeClient dashscopeClient;

    public VectorEncoding(DashscopeClient dashscopeClient) {
        this.dashscopeClient = dashscopeClient;
    }

    /**
     * 对文本列表进行向量编码
     *
     * @param textList 待编码的文本列表
     * @return 对应的向量数组列表
     */
    public List<float[]> encode(List<String> textList) {
        if (textList == null || textList.isEmpty()) {
            return List.of();
        }

        try {
            log.debug("启动向量编码任务，文本数: {}", textList.size());

            List<float[]> vectors = dashscopeClient.embed(textList);

            log.debug("向量编码完成，共生成 {} 个向量", vectors.size());
            return vectors;
        } catch (Exception e) {
            log.error("向量编码失败: {}", e.getMessage(), e);
            throw new RuntimeException("向量编码过程出错", e);
        }
    }
}
