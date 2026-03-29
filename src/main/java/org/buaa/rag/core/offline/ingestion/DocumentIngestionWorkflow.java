package org.buaa.rag.core.offline.ingestion;

import static org.buaa.rag.common.enums.OfflineErrorCodeEnum.DOCUMENT_PARSE_FAILED;
import static org.buaa.rag.common.enums.OfflineErrorCodeEnum.DOCUMENT_SIZE_EXCEEDED;
import static org.buaa.rag.common.enums.OfflineErrorCodeEnum.DOCUMENT_TYPE_NOT_SUPPORTED;
import static org.buaa.rag.common.enums.OfflineErrorCodeEnum.DOCUMENT_UPLOAD_NULL;

import java.util.List;

import org.buaa.rag.common.convention.exception.ClientException;
import org.buaa.rag.common.convention.exception.DocumentIngestionException;
import org.buaa.rag.common.convention.exception.ServiceException;
import org.buaa.rag.dao.entity.DocumentDO;
import org.buaa.rag.core.model.ContentFragment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 文档离线摄取工作流
 * <p>
 * 仅负责编排生命周期、内容提取与产物写入，具体实现下沉到专门组件。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentIngestionWorkflow {

    private static final int MAX_FAILURE_REASON_LENGTH = 512;

    private final DocumentLifecycleService lifecycleService;
    private final DocumentContentPipeline contentPipeline;
    private final DocumentArtifactService artifactService;

    public void process(DocumentIngestionTask task) {
        DocumentDO document = loadDocument(task.documentId());
        if (document == null) {
            return;
        }
        // 通过状态机门禁避免重复消费：仅允许 PENDING -> PROCESSING 的单向迁移。
        if (!lifecycleService.markProcessing(document.getId())) {
            log.info("文档状态不可更新，跳过异步摄取: documentId={}", task.documentId());
            return;
        }

        try {
            List<ContentFragment> fragments = contentPipeline.extract(document, task.chunkMode());
            artifactService.replace(document, fragments);
            lifecycleService.markCompleted(document.getId());
        } catch (Exception exception) {
            // 异步链路采用“失败即清理”策略，尽量避免 chunk/ES/Milvus 部分成功造成脏数据。
            artifactService.cleanup(document);
            throw toIngestionException(exception);
        }
    }

    public void markFailed(Long documentId, String failureReason) {
        DocumentDO document = loadDocument(documentId);
        if (document == null) {
            return;
        }
        // 达到重试上限后由消费者调用，统一走清理+失败落库。
        artifactService.cleanup(document);
        lifecycleService.markFailed(documentId, failureReason);
    }

    private DocumentDO loadDocument(Long documentId) {
        DocumentDO document = lifecycleService.findDocumentById(documentId);
        if (document == null) {
            log.info("文档记录不存在，跳过异步摄取: documentId={}", documentId);
        }
        return document;
    }

    public static DocumentIngestionException toIngestionException(Exception exception) {
        if (exception instanceof DocumentIngestionException ingestionException) {
            return ingestionException;
        }
        boolean retryable = isRetryable(exception);
        if (exception instanceof ServiceException serviceException) {
            return new DocumentIngestionException(
                serviceException.getErrorMessage(),
                serviceException,
                retryable
            );
        }
        return new DocumentIngestionException(
            "文档处理失败: " + exception.getMessage(),
            exception,
            retryable
        );
    }

    public static boolean isRetryable(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof DocumentIngestionException ingestionException) {
                return ingestionException.isRetryable();
            }
            if (current instanceof ClientException) {
                return false;
            }
            if (current instanceof ServiceException serviceException && isNonRetryable(serviceException)) {
                return false;
            }
            current = current.getCause();
        }
        return true;
    }

    public static String summarizeFailureReason(Throwable throwable) {
        Throwable root = rootCause(throwable);
        String message = root.getMessage();
        String prefix = root.getClass().getSimpleName();
        String summary = StringUtils.hasText(message) ? prefix + ": " + message.trim() : prefix;
        return normalizeFailureReason(summary);
    }

    public static String normalizeFailureReason(String failureReason) {
        if (!StringUtils.hasText(failureReason)) {
            return null;
        }
        String value = failureReason.trim();
        if (value.length() <= MAX_FAILURE_REASON_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_FAILURE_REASON_LENGTH);
    }

    private static boolean isNonRetryable(ServiceException serviceException) {
        String errorCode = serviceException.getErrorCode();
        return DOCUMENT_PARSE_FAILED.code().equals(errorCode)
            || DOCUMENT_TYPE_NOT_SUPPORTED.code().equals(errorCode)
            || DOCUMENT_SIZE_EXCEEDED.code().equals(errorCode)
            || DOCUMENT_UPLOAD_NULL.code().equals(errorCode);
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable root = throwable;
        while (root != null && root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root == null ? new RuntimeException("未知异常") : root;
    }
}
