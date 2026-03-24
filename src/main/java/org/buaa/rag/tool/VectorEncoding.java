package org.buaa.rag.tool;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 向量编码服务
 * 统一通过 Spring AI EmbeddingModel 调用阿里云 DashScope
 */
@Component
public class VectorEncoding {

    private static final Logger log = LoggerFactory.getLogger(VectorEncoding.class);

    @Value("${rag.embedding.batch-size:10}")
    private int processingBatchSize;

    private final EmbeddingModel embeddingModel;

    public VectorEncoding(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
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

            for (int i = 0; i < batches.size(); i++) {
                List<String> batch = batches.get(i);
                log.debug("Spring AI 处理第 {}/{} 批，大小: {}", i + 1, batches.size(), batch.size());
                allVectors.addAll(embeddingModel.embed(batch));
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

        for (int i = 0; i < textList.size(); i += batchSize) {
            int endIndex = Math.min(i + batchSize, textList.size());
            batches.add(textList.subList(i, endIndex));
        }

        return batches;
    }
}
