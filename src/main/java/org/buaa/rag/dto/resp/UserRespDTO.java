package org.buaa.rag.dto.resp;

import lombok.Data;

/**
 * 用户信息返回参数响应
 */
@Data
public class UserRespDTO {

    /**
     * id
     */
    private Long id;

    /**
     * 邮箱
     */
    private String mail;

    /**
     * 用户名
     */
    private String username;

    /**
     * 头像
     */
    private String avatar;

    /**
     * 是否管理员：1-管理员，0-普通用户
     */
    private Integer isAdmin;

}
