package org.buaa.rag.common.enums;

import lombok.Getter;

/**
 * 文档上传状态码
 */
@Getter
public enum UploadStatusEnum {

    PENDING(0, "待处理"),
    PROCESSING(1, "处理中"),
    COMPLETED(2, "已完成"),
    FAILED_FINAL(-1, "失败（最终）");

    private final int code;
    private final String desc;

    UploadStatusEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static UploadStatusEnum fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (UploadStatusEnum status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return null;
    }

    public static String descOf(Integer code) {
        UploadStatusEnum status = fromCode(code);
        return status == null ? "未知状态" : status.getDesc();
    }
}
