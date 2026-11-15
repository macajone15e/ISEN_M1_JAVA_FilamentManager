package com.filamentmanager.operation;

import com.filamentmanager.model.*;

public abstract class StockOperation {
    private final String spoolId;

    protected StockOperation(String spoolId) {
        this.spoolId = Validation.text(spoolId, "Identifiant bobine");
    }

    public final String spoolId() {
        return spoolId;
    }

    public abstract void validate(Spool spool);
    protected abstract void apply(Spool spool);
    public final void execute(Spool spool) {
        validate(spool);
        apply(spool);
    }
}
