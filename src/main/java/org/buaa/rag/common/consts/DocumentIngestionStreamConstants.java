package org.buaa.rag.common.consts;

/**
 * 文档摄取异步任务流字段常量
 */
public final class DocumentIngestionStreamConstants {

    private DocumentIngestionStreamConstants() {
    }

    public static final String FIELD_DOCUMENT_MD5 = "documentMd5";
    public static final String FIELD_FILE_NAME = "fileName";
    public static final String FIELD_RETRY_COUNT = "retryCount";
    public static final String FIELD_ENQUEUED_AT = "enqueuedAt";
}
