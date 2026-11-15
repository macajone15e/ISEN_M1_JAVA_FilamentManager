package com.filamentmanager.service;

import com.filamentmanager.model.*;
import com.filamentmanager.exception.InvalidDataException;

import java.time.*;

public final class DryingService {
    private final InventoryService inventory;
    private final Clock clock;
    private final Runnable onChange;

    public DryingService(InventoryService inventory, Clock clock, Runnable onChange) {
        this.inventory = inventory;
        this.clock = clock;
        this.onChange = onChange;
    }

    public String recommendations(String spoolId) {
        Spool spool = inventory.require(spoolId);
        Material.Properties properties = spool.material().properties();
        return properties.name() + " : " + properties.dryingTemperature() + " °C pendant " + properties.dryingDuration()
                + " h (indicatif, consulter le fabricant). Dernier séchage : " + spool.lastDrying();
    }

    public LocalDateTime calculateEnd(String spoolId, LocalDateTime start) {
        Validation.required(start, "Début séchage");
        double hours = inventory.require(spoolId).material().properties().dryingDuration();
        try {
            long durationNanoseconds = Math.round(hours * 3_600_000_000_000d);
            return start.plusNanos(durationNanoseconds);
        } catch (DateTimeException | ArithmeticException e) {
            throw new InvalidDataException("Fin théorique hors limites", e);
        }
    }

    public void record(String spoolId, LocalDateTime end) {
        inventory.require(spoolId).recordDrying(end, clock);
        onChange.run();
    }
}
