package org.buaa.rag.tool;

/**
 * 知识库名称转换工具类。
 * <p>
 * 数据库中 knowledge.name 统一使用下划线命名（如 {@code integrated_kb}），
 * 但不同外部存储组件对名称字符有各自限制：
 * <ul>
 *   <li><b>RustFS / S3</b>：Bucket 名称不允许下划线，需要将 {@code _} 转为 {@code -}</li>
 *   <li><b>Milvus</b>：Collection 名称不允许连字符，需要将 {@code -} 转为 {@code _}</li>
 * </ul>
 */
public final class KnowledgeNameConverter {

    private KnowledgeNameConverter() {
        // 工具类，禁止实例化
    }

    /**
     * 将知识库 name 转换为合法的 RustFS / S3 Bucket 名称。
     */
    public static String toBucketName(String knowledgeName) {
        if (knowledgeName == null) {
            return "";
        }
        return knowledgeName.replace('_', '-');
    }

    /**
     * 将知识库 name 转换为合法的 Milvus Collection 名称。
     */
    public static String toCollectionName(String knowledgeName) {
        if (knowledgeName == null) {
            return "";
        }
        return knowledgeName.replace('-', '_');
    }
}
