package com.filamentmanager.operation;

import com.filamentmanager.model.Spool;

public final class StockAdjustment extends StockOperation {
    private final double measurement;

    public StockAdjustment(String id, double measurement) {
        super(id);
        this.measurement = measurement;
    }

    @Override
    public void validate(Spool spool) {
        spool.checkAdjustment(measurement);
    }

    @Override
    protected void apply(Spool spool) {
        spool.adjust(measurement);
    }
}
