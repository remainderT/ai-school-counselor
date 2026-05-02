package org.buaa.rag.dto.resp;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通用分页响应 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResponseDTO<T> {

    private List<T> records;

    private long total;

    private long size;

    private long current;

    private long pages;
}
