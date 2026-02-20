package org.buaa.rag.service;

import java.util.List;

public interface QueryDecomposer {

    /**
     * 将复杂问题拆分为若干子问题（最多配置数），不足则返回原问题列表
     */
    List<String> decompose(String query);
}
