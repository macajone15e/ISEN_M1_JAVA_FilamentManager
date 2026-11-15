package com.filamentmanager.service;

import com.filamentmanager.model.*;
import com.filamentmanager.repository.StockRepository;
import com.filamentmanager.estimator.ConsumptionEstimator;

import java.io.IOException;
import java.time.Clock;

public final class ApplicationSession {
    private final StockRepository repository;
    private final Configuration configuration;
    private final CatalogService catalog;
    private final InventoryService inventory;
    private final PrintService prints;
    private final DryingService drying;
    private final StatisticsService statistics;
    private boolean modified;

    public ApplicationSession(StockRepository repository, ConsumptionEstimator estimator, Clock clock) throws IOException {
        this.repository = repository;
        StockRepository.LoadResult loadResult = repository.load();
        ApplicationData data = loadResult.data();
        configuration = data.configuration();
        // Examples exist only in memory until the first explicit save.
        modified = loadResult.examples();
        Runnable onChange = () -> modified = true;
        catalog = new CatalogService(data.materials(), onChange);
        inventory = new InventoryService(data.spools(), catalog, onChange);
        prints = new PrintService(data.projects(), catalog, inventory, estimator, clock, onChange);
        drying = new DryingService(inventory, clock, onChange);
        statistics = new StatisticsService(catalog, inventory, prints);
    }

    public ApplicationData data() {
        return new ApplicationData(catalog.list(), inventory.list(), prints.list(), configuration);
    }

    public void save() throws IOException {
        repository.save(data());
        // Keep the dirty flag if persistence throws, so the user can retry without losing edits.
        modified = false;
    }

    public boolean modified() {
        return modified;
    }

    public Configuration configuration() {
        return configuration;
    }

    public CatalogService catalog() {
        return catalog;
    }

    public InventoryService inventory() {
        return inventory;
    }

    public PrintService prints() {
        return prints;
    }

    public DryingService drying() {
        return drying;
    }

    public StatisticsService statistics() {
        return statistics;
    }
}
