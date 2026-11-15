package com.filamentmanager.operation;

import com.filamentmanager.model.Spool;

public final class StockConsumption extends StockOperation {
    private final double quantity;

    public StockConsumption(String id, double quantity) {
        super(id);
        this.quantity = quantity;
    }

    @Override
    public void validate(Spool spool) {
        spool.checkConsumption(quantity);
    }

    @Override
    protected void apply(Spool spool) {
        spool.consume(quantity);
    }
}
