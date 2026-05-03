package org.buaa.rag;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.buaa.rag.core.offline.index.MilvusCollectionManager;
import org.buaa.rag.dao.entity.KnowledgeDO;
import org.buaa.rag.dao.mapper.KnowledgeMapper;
import org.buaa.rag.tool.BucketManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

/**
 * 存量知识库存储资源迁移脚本（幂等，可重复执行）
 * <p>
 * 职责：
 * 1. 在 RustFS 中按 toBucketName(knowledge.name) 创建 Bucket（已存在则跳过）
 *    - RustFS Bucket 名：下划线转连字符，如 academic_kb -> academic-kb
 * 2. 在 Milvus 中按 knowledge.name 创建 Collection（已存在则跳过）
 *    - Milvus Collection 名：直接使用 knowledge.name（下划线格式），如 academic_kb
 * <p>
 * 运行方式：
 *   mvn test -Dtest=KnowledgeStorageMigrationTest -pl .
 */
@Slf4j
@SpringBootTest
class KnowledgeStorageMigrationTest {

    @Autowired
    private KnowledgeMapper knowledgeMapper;

    @Autowired
    private BucketManager bucketManager;

    @Autowired
    private MilvusCollectionManager collectionManager;

    @Test
    void migrateStorageResources() {
        List<KnowledgeDO> allKnowledge = knowledgeMapper.selectList(
            Wrappers.lambdaQuery(KnowledgeDO.class).eq(KnowledgeDO::getDelFlag, 0)
        );

        log.info("共找到 {} 个存量知识库，开始迁移...", allKnowledge.size());

        int successBucket = 0, successCollection = 0, failCount = 0;

        for (KnowledgeDO kb : allKnowledge) {
            String kbName = kb.getName();                        // 下划线，如 academic_kb
            String bucketName = BucketManager.toBucketName(kbName); // 连字符，如 academic-kb
            log.info("处理知识库: id={}, name={}, bucket={}", kb.getId(), kbName, bucketName);

            // 1. 创建 RustFS Bucket（连字符格式）
            try {
                bucketManager.ensureBucket(bucketName);
                log.info("  [RustFS] Bucket 就绪: {}", bucketName);
                successBucket++;
            } catch (Exception e) {
                log.error("  [RustFS] Bucket 创建失败: {}, error={}", bucketName, e.getMessage());
                failCount++;
            }

            // 2. 创建 Milvus Collection（下划线格式，即 knowledge.name 原值）
            try {
                collectionManager.ensureCollection(kbName);
                log.info("  [Milvus] Collection 就绪: {}", kbName);
                successCollection++;
            } catch (Exception e) {
                log.error("  [Milvus] Collection 创建失败: {}, error={}", kbName, e.getMessage());
                failCount++;
            }
        }

        log.info("迁移完成 —— 总计: {}, Bucket 成功: {}, Collection 成功: {}, 失败: {}",
            allKnowledge.size(), successBucket, successCollection, failCount);

        if (failCount > 0) {
            throw new IllegalStateException("部分知识库存储资源迁移失败，请检查上方日志");
        }
    }
}
