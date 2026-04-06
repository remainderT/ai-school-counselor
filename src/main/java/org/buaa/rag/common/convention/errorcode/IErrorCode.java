package org.buaa.rag.common.convention.errorcode;

/**
 * 统一错误码契约，所有业务错误码枚举均须实现此接口。
 * <p>
 * 编码规则：A 前缀表示客户端异常，B 前缀表示服务端异常，C 前缀表示第三方服务异常。
 */
public interface IErrorCode {

    /** 获取错误编号 */
    String code();

    /** 获取可读错误描述 */
    String message();
}
