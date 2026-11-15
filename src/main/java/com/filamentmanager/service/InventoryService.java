package com.filamentmanager.service;

import com.filamentmanager.model.*;
import com.filamentmanager.operation.StockOperation;
import com.filamentmanager.exception.*;

import java.math.*;
import java.util.*;
import java.util.function.Predicate;

public final class InventoryService {
    private final Map<String, Spool> spools = new LinkedHashMap<>();
    private final CatalogService catalog;
    private final Runnable onChange;

    public InventoryService(Collection<Spool> initialItems, CatalogService catalog, Runnable onChange) {
        this.catalog = catalog;
        this.onChange = onChange;
        for (Spool spool : initialItems) {
            insert(spool);
        }
    }

    private void insert(Spool spool) {
        Validation.required(spool, "Bobine");
        catalog.validateReference(spool.material());
        if (spools.putIfAbsent(spool.id(), spool) != null) {
            throw new InvalidDataException("Bobine dupliquée : " + spool.id());
        }
    }

    public void add(Spool spool) {
        insert(spool);
        onChange.run();
    }

    public Optional<Spool> find(String id) {
        return Optional.ofNullable(spools.get(id));
    }

    public Spool require(String id) {
        Spool spool = spools.get(id);
        if (spool == null) {
            throw new SpoolNotFoundException(id);
        }
        return spool;
    }

    public List<Spool> list() {
        return List.copyOf(spools.values());
    }

    public List<Spool> filter(Predicate<Spool> filter) {
        Objects.requireNonNull(filter);
        List<Spool> result = new ArrayList<>();
        for (Spool spool : spools.values()) {
            if (filter.test(spool)) {
                result.add(spool);
            }
        }
        return List.copyOf(result);
    }

    public List<Spool> lowStock(double threshold) {
        Validation.nonNegative(threshold, "Seuil");
        List<Spool> result = new ArrayList<>();
        for (Spool spool : spools.values()) {
            if (spool.remainingWeight() < threshold) {
                result.add(spool);
            }
        }
        return List.copyOf(result);
    }

    public double availableWeight() {
        return totalWeight(list());
    }

    public static double totalWeight(Collection<Spool> spools) {
        // Compensated summation preserves precision when adding decimal weights.
        DoubleSummaryStatistics weight = new DoubleSummaryStatistics();
        for (Spool spool : spools) {
            weight.accept(spool.remainingWeight());
        }
        return Validation.nonNegative(weight.getSum(), "Total poids");
    }

    public BigDecimal remainingValue() {
        return value(list());
    }

    public static BigDecimal value(Collection<Spool> spools) {
        BigDecimal totalValue = BigDecimal.ZERO;
        for (Spool spool : spools) {
            BigDecimal spoolValue = spool.unroundedCost(spool.remainingWeight());
            totalValue = totalValue.add(spoolValue);
        }
        // Rounding only the total avoids accumulating rounding errors from individual spools.
        return totalValue.setScale(2, RoundingMode.HALF_UP);
    }

    public void executeOperation(StockOperation operation) {
        Validation.required(operation, "Opération");
        operation.execute(require(operation.spoolId()));
        onChange.run();
    }
}
