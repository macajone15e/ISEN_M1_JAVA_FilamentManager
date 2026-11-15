package com.filamentmanager.model;

public record Configuration(double lowStockThreshold) {
    public Configuration {
        Validation.nonNegative(lowStockThreshold, "Seuil stock faible");
    }
}
