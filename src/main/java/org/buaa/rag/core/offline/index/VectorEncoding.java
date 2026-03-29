package org.buaa.rag.core.offline.index;

import java.util.ArrayList;
import java.util.List;

import org.buaa.rag.tool.DashscopeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 向量编码服务
 * 通过 DashScope HTTP 接口执行向量化
 */
@Component
public class VectorEncoding {

    private static final Logger log = LoggerFactory.getLogger(VectorEncoding.class);

    @Value("${rag.embedding.batch-size:10}")
    private int processingBatchSize;

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
            log.info("启动向量编码任务，文本总数: {}", textList.size());

            List<float[]> allVectors = new ArrayList<>(textList.size());
            List<List<String>> batches = partitionIntoBatches(textList);

            // 批处理调用 embedding，避免单次请求过大。
            for (int i = 0; i < batches.size(); i++) {
                List<String> batch = batches.get(i);
                log.debug("DashScope 处理第 {}/{} 批，大小: {}", i + 1, batches.size(), batch.size());
                allVectors.addAll(dashscopeClient.embed(batch));
            }

            log.info("向量编码完成，共生成 {} 个向量", allVectors.size());
            return allVectors;
        } catch (Exception e) {
            log.error("向量编码失败: {}", e.getMessage(), e);
            throw new RuntimeException("向量编码过程出错", e);
        }
    }

    /**
     * 将文本列表分批
     */
    private List<List<String>> partitionIntoBatches(List<String> textList) {
        List<List<String>> batches = new ArrayList<>();
        int batchSize = Math.max(1, processingBatchSize);

        // subList 仅做视图切片，避免复制大列表带来的额外内存开销。
        for (int i = 0; i < textList.size(); i += batchSize) {
            int endIndex = Math.min(i + batchSize, textList.size());
            batches.add(textList.subList(i, endIndex));
        }

        return batches;
    }
}
