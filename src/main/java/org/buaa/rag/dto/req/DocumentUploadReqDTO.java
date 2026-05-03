package org.buaa.rag.dto.req;

import jakarta.validation.constraints.NotNull;

import org.springframework.web.multipart.MultipartFile;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一文档上传请求
 * <p>
 * 支持文件上传和 URL 上传两种来源。
 * file 和 url 至少需要提供一个，此约束由 Service 层校验。
 */
@Data
@NoArgsConstructor
public class DocumentUploadReqDTO {

    private MultipartFile file;

    private String url;

    /**
     * 是否启用 URL 定时更新，仅 URL 上传生效
     */
    private Boolean scheduleEnabled;

    /**
     * Spring cron 表达式，仅 URL 且 scheduleEnabled=true 时必填
     */
    private String scheduleCron;

    @NotNull(message = "知识库 ID 不能为空")
    private Long knowledgeId;

    /**
     * 分块策略，可选值：fixed_size / structure_aware
     */
    private String chunkMode;
}
