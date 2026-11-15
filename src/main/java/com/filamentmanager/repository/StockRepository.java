package com.filamentmanager.repository;

import com.filamentmanager.model.ApplicationData;

import java.io.IOException;

public interface StockRepository {
    record LoadResult(ApplicationData data, boolean examples) { }
    LoadResult load() throws IOException;
    void save(ApplicationData data) throws IOException;
}
