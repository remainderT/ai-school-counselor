package org.buaa.rag.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * 文档解析配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "file.parsing")
public class FIleParseProperties {

    /** 分块模式: fixed_size / structure_aware */
    private String chunkMode = "structure_aware";

    /** 文本分块大小 */
    private int chunkSize = 512;

    /** 相邻块重叠字符 */
    private int overlapSize = 128;

    /** 结构感知分块目标字符 */
    private int semanticTargetChars = 1400;

    /** 结构感知分块最大字符 */
    private int semanticMaxChars = 1800;

    /** 结构感知分块最小字符 */
    private int semanticMinChars = 600;

    /** 结构感知分块重叠字符 */
    private int semanticOverlapChars = 0;

    /** 缓冲区大小 */
    private int bufferSize = 8192;

    /** 最大内存占用阈值 (0~1) */
    private double maxMemoryThreshold = 0.8;

    /** 单次上传最大字节数 (默认 100 MB) */
    private long maxUploadBytes = 104857600L;

    /** 提取文本最大字符数 */
    private int maxExtractedChars = 800000;

    /** 清洗后文本最大字符数 */
    private int maxCleanedChars = 500000;

    /** 是否启用 PDF OCR */
    private boolean enableOcr = false;
}
