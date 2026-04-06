package org.buaa.rag.service;

import java.util.List;

import org.buaa.rag.dto.req.IntentNodeCreateReqDTO;
import org.buaa.rag.dto.req.IntentNodeUpdateReqDTO;
import org.buaa.rag.dto.resp.IntentNodeTreeRespDTO;

public interface IntentTreeService {

    List<IntentNodeTreeRespDTO> tree();

    Long create(IntentNodeCreateReqDTO requestParam);

    void update(Long id, IntentNodeUpdateReqDTO requestParam);

    void delete(Long id);

    void batchEnable(List<Long> ids);

    void batchDisable(List<Long> ids);

    void batchDelete(List<Long> ids);
}
