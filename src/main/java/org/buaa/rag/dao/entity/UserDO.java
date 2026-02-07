package org.buaa.rag.dao.entity;

import org.buaa.rag.common.database.BaseDO;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("user")
public class UserDO extends BaseDO {

    private Long id;

    private String username;

    private String password;

    private String avatar;

    private String mail;

    private String salt;

}
