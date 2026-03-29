package org.buaa.rag.core.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * 内容片段DTO
 * 用于传递文本分块数据
 *
 * @author campus-team
 */
@Getter
@Setter
@AllArgsConstructor
public class ContentFragment {

    /**
     * 片段序号
     */
    private int fragmentId;

    /**
     * 片段内容
     */
    private String textContent;

    /**
     * 判断内容是否为空
     */
    public boolean isEmpty() {
        return textContent == null || textContent.trim().isEmpty();
    }
}
