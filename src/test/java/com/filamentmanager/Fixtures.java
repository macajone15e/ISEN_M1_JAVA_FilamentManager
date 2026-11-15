package com.filamentmanager;

import com.filamentmanager.model.*;
import com.filamentmanager.service.*;
import com.filamentmanager.estimator.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

final class Fixtures {
    // A fixed clock makes date validation and execution reports reproducible.
    static final Clock CLOCK = Clock.fixed(Instant.parse("2025-05-10T12:00:00Z"), ZoneOffset.UTC);

    static Material.Properties properties(String name, double min, double max, double loss) {
        return new Material.Properties(name, min, max, 0, 60, 45, 4, 1.24, loss);
    }

    static Material material() {
        return new Material("PLA", properties("PLA", 190, 220, .05));
    }

    static Spool spool(Material m) {
        return spool("B", m, 1000, 1000, "20.00", 1.75);
    }

    static Spool spool(String id, Material m, double initial, double remaining, String price, double diameter) {
        return new Spool(id, m, "Marque", "Bleu", null, initial, remaining, diameter,
                new BigDecimal(price), LocalDate.of(2024, 1, 1), null, CLOCK);
    }

    static PrintProject project(Material m) {
        return project("P", m, 100, 1.75, 205, 55);
    }

    static PrintProject project(String id, Material m, double weight, double diameter, double nozzle, double bed) {
        return new PrintProject(id, new PrintProject.Parameters("Support", m, diameter, weight, nozzle, bed));
    }

    static final class Business {
        final Material m = material();
        final Spool b = spool(m);
        final PrintProject p = project(m);
        int changes;
        final Runnable onChange = () -> changes++;
        final CatalogService catalog = new CatalogService(List.of(m), onChange);
        final InventoryService inventory = new InventoryService(List.of(b), catalog, onChange);
        final PrintService prints = new PrintService(
                List.of(p), catalog, inventory, new LossAdjustedEstimator(), CLOCK, onChange);
        final DryingService drying = new DryingService(inventory, CLOCK, onChange);

        ApplicationData data() {
            return new ApplicationData(catalog.list(), inventory.list(), prints.list(), new Configuration(100));
        }
    }
}
