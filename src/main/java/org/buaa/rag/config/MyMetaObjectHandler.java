package org.buaa.rag.config;

import java.util.Date;

import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;

/**
 * MyBatis-Plus 自动填充：在 INSERT/UPDATE 时自动设置审计字段。
 */
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject meta) {
        Date now = new Date();
        this.strictInsertFill(meta, "createTime", () -> now, Date.class);
        this.strictInsertFill(meta, "updateTime", () -> now, Date.class);
        this.strictInsertFill(meta, "delFlag", () -> 0, Integer.class);
    }

    @Override
    public void updateFill(MetaObject meta) {
        this.strictUpdateFill(meta, "updateTime", Date::new, Date.class);
    }
}