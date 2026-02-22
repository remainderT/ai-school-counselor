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

    /** 文本分块大小 */
    private int chunkSize = 512;

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
