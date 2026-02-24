package com.calai.backend.foodlog.barcode;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class BarcodeCachePurgerTx {

    private final BarcodeLookupCacheRepository repo;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteBatch(List<BarcodeLookupCacheEntity> batch) {
        if (batch == null || batch.isEmpty()) return 0;
        repo.deleteAllInBatch(batch);
        return batch.size();
    }
}
