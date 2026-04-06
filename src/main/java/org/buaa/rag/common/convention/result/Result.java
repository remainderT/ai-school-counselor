package org.buaa.rag.common.convention.result;

import java.io.Serial;
import java.io.Serializable;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * REST 接口统一响应信封。
 * <p>
 * 前端约定：{@code code == "0"} 代表请求成功，其余均视为异常。
 */
@Data
@Accessors(chain = true)
public class Result<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 8127304561920476853L;

    /** 成功时的固定状态码 */
    public static final String SUCCESS_CODE = "0";

    private String code;

    private String message;

    private T data;

    /** 判断本次请求是否成功 */
    public boolean isSuccess() {
        return SUCCESS_CODE.equals(code);
    }
}