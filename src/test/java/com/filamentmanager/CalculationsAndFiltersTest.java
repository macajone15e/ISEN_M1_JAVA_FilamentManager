package com.filamentmanager;

import com.filamentmanager.exception.InvalidQuantityException;
import com.filamentmanager.model.Spool;
import com.filamentmanager.model.Material;
import com.filamentmanager.model.PrintProject;
import com.filamentmanager.service.InventoryService;
import com.filamentmanager.service.StatisticsService;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

import static com.filamentmanager.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class CalculationsAndFiltersTest {
    @Test
    void weightSumPreservesSmallQuantities() {
        Material material = material();
        Spool largeSpool = spool("Grande", material, 1e16, 1e16, "0", 1.75);
        Spool firstSmall = spool("Petite1", material, 1, 1, "0", 1.75);
        Spool secondSmall = spool("Petite2", material, 1, 1, "0", 1.75);

        double totalWeight = InventoryService.totalWeight(List.of(largeSpool, firstSmall, secondSmall));

        // Naive summation would lose the two grams added to the large total.
        assertEquals(10_000_000_000_000_002d, totalWeight);
        assertEquals(0, InventoryService.totalWeight(List.of()));
    }

    @Test
    void weightSumRejectsInfiniteTotal() {
        Material material = material();
        Spool first = spool("B1", material, Double.MAX_VALUE, Double.MAX_VALUE, "0", 1.75);
        Spool second = spool("B2", material, Double.MAX_VALUE, Double.MAX_VALUE, "0", 1.75);

        assertThrows(InvalidQuantityException.class,
                () -> InventoryService.totalWeight(List.of(first, second)));
    }

    @Test
    void filtersPreserveOrderImmutabilityAndState() {
        Business business = new Business();
        Spool second = spool("B2", business.m, 100, 50, "1.00", 1.75);
        Spool otherDiameter = spool("B3", business.m, 100, 50, "1.00", 2.85);
        business.inventory.add(second);
        business.inventory.add(otherDiameter);
        int previousChanges = business.changes;

        List<Spool> filtered = business.inventory.filter(spool -> spool.diameter() == 1.75);
        List<Spool> compatibleItems = business.prints.compatibleSpools("P");
        List<Spool> low = business.inventory.lowStock(100);

        assertEquals(List.of(business.b, second), filtered);
        assertEquals(filtered, compatibleItems);
        assertEquals(List.of(second, otherDiameter), low);
        assertThrows(UnsupportedOperationException.class, filtered::clear);
        assertThrows(UnsupportedOperationException.class, compatibleItems::clear);
        assertThrows(UnsupportedOperationException.class, low::clear);
        assertEquals(previousChanges, business.changes);
    }

    @Test
    void statisticsIgnoreUnfinishedProjectsAndIncludeMaterialsWithoutStock() {
        Business business = new Business();
        Material otherMaterial = new Material("PETG", business.m.properties());
        business.catalog.add(otherMaterial);
        PrintProject cancelled = project("Annule", business.m, 200, 1.75, 205, 55);
        PrintProject planned = project("Planifie", business.m, 300, 1.75, 205, 55);
        business.prints.create(cancelled);
        business.prints.create(planned);
        business.prints.cancel("Annule");
        business.prints.complete("P", "B", 80);
        StatisticsService statistics = new StatisticsService(business.catalog, business.inventory, business.prints);

        List<StatisticsService.ByMaterial> summaries = statistics.byMaterial();

        assertEquals(2, summaries.size());
        assertEquals("PLA", summaries.get(0).materialId());
        assertEquals(statistics.total(), summaries.get(0).summary());
        assertEquals(80, statistics.total().actualConsumption());
        assertEquals(new BigDecimal("1.60"), statistics.total().actualCost());
        assertEquals("PETG", summaries.get(1).materialId());
        assertEquals(new StatisticsService.Summary(0, new BigDecimal("0.00"), 0, new BigDecimal("0.00")),
                summaries.get(1).summary());
        assertThrows(UnsupportedOperationException.class, summaries::clear);
    }
}
