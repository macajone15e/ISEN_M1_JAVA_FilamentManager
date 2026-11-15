package com.filamentmanager;

import com.filamentmanager.model.*;
import com.filamentmanager.operation.*;
import com.filamentmanager.estimator.*;
import com.filamentmanager.service.*;
import com.filamentmanager.exception.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static com.filamentmanager.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class BusinessTest {
    @ParameterizedTest
    @ValueSource(doubles = {0, -1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void strictlyPositiveQuantities(double value) {
        var m = material();
        var b = spool(m);

        assertThrows(InvalidQuantityException.class, () -> b.consume(value));
        assertThrows(InvalidQuantityException.class, () -> project("P", m, value, 1.75, 205, 55));
        assertThrows(InvalidQuantityException.class, () -> spool("B", m, value, 0, "0", 1.75));
        assertThrows(InvalidQuantityException.class, () -> spool("B", m, 100, 0, "0", value));
        assertEquals(1000, b.remainingWeight());
    }

    @ParameterizedTest
    @ValueSource(doubles = {-1, Double.NaN, Double.POSITIVE_INFINITY})
    void nonNegativeValues(double value) {
        assertThrows(InvalidQuantityException.class, () -> new Configuration(value));
        assertThrows(InvalidQuantityException.class, () -> spool(material()).adjust(value));
        assertThrows(InvalidQuantityException.class, () -> project("P", material(), 10, 1.75, value, 50));
    }

    @Test
    void invalidMaterial() {
        assertThrows(InvalidDataException.class, () -> new Material(" ", material().properties()));
        assertThrows(InvalidDataException.class, () -> properties("", 190, 220, .05));
        assertThrows(InvalidDataException.class, () -> properties("PLA", 220, 190, .05));
        for (double rate : new double[]{-1, 1.01, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(InvalidDataException.class, () -> properties("PLA", 190, 220, rate));
        }
        assertThrows(InvalidDataException.class, () -> new Material.Properties("PLA", 190, 220, 60, 0, 45, 4, 1, 0));
        assertThrows(InvalidDataException.class, () -> new Material.Properties("PLA", 190, 220, 0, 60, 45, 0, 1, 0));
        assertThrows(InvalidDataException.class, () -> new Material.Properties("PLA", 190, 220, 0, 60, 45, 4, 0, 0));
    }

    @Test
    void fieldsAndDates() {
        var m = material();

        assertThrows(InvalidDataException.class,
                () -> new Spool("B", m, " ", "bleu", "", 10, 10, 1.75, BigDecimal.ZERO, LocalDate.of(2024, 1, 1), null, CLOCK));
        assertThrows(InvalidDataException.class,
                () -> new Spool("B", m, "X", " ", "", 10, 10, 1.75, BigDecimal.ZERO, LocalDate.of(2024, 1, 1), null, CLOCK));
        assertThrows(InvalidDataException.class,
                () -> new Spool("B", m, "X", "bleu", "", 10, 10, 1.75, BigDecimal.ZERO, LocalDate.now(CLOCK).plusDays(1), null, CLOCK));
        assertThrows(InvalidDataException.class,
                () -> new Spool("B", m, "X", "bleu", "", 10, 10, 1.75, BigDecimal.ZERO, LocalDate.now(CLOCK), LocalDateTime.now(CLOCK).plusSeconds(1), CLOCK));
        assertThrows(InvalidDataException.class, () -> spool("B", m, 100, 101, "0", 1.75));
        assertThrows(InvalidDataException.class, () -> spool("B", m, 100, 10, "-1", 1.75));
        assertThrows(InvalidDataException.class, () -> spool("B", m, 100, 10, "1.001", 1.75));
        assertThrows(InvalidDataException.class, () -> new PrintProject("", project(m).parameters()));
    }

    @Test
    void polymorphicOperationsAndAtomicity() {
        var s = new Business();
        StockOperation consumption = new StockConsumption("B", 1000);
        s.inventory.executeOperation(consumption);
        assertEquals(0, s.b.remainingWeight());
        assertThrows(InsufficientStockException.class,
                () -> s.inventory.executeOperation(new StockConsumption("B", 1)));
        assertEquals(0, s.b.remainingWeight());
        assertEquals(1, s.changes);
        StockOperation adjustment = new StockAdjustment("B", 1000);
        s.inventory.executeOperation(adjustment);
        assertEquals(1000, s.b.remainingWeight());
        assertThrows(InvalidQuantityException.class,
                () -> s.inventory.executeOperation(new StockAdjustment("B", 1001)));
        assertEquals(1000, s.b.remainingWeight());
        s.inventory.executeOperation(new StockAdjustment("B", 0));
        assertEquals(0, s.b.remainingWeight());
        assertThrows(SpoolNotFoundException.class, () -> s.inventory.executeOperation(new StockAdjustment("?", 0)));
    }

    @Test
    void catalogInventoryAndReferences() {
        var s = new Business();

        assertThrows(InvalidDataException.class, () -> s.catalog.add(material()));
        assertThrows(InvalidDataException.class, () -> s.inventory.add(spool(s.m)));
        assertThrows(InvalidDataException.class,
                () -> s.inventory.add(spool("Autre", material(), 100, 100, "0", 1.75)));
        assertThrows(InvalidDataException.class, () -> s.prints.create(project(s.m)));
        assertThrows(InvalidDataException.class,
                () -> s.prints.create(project("Autre", material(), 10, 1.75, 200, 50)));
        assertTrue(s.inventory.find("?").isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> s.inventory.list().clear());
        assertEquals(List.of(s.b), s.inventory.filter(b -> b.color().equals("Bleu")));
        s.b.adjust(100);
        assertTrue(s.inventory.lowStock(100).isEmpty());
        s.b.adjust(99);
        assertEquals(List.of(s.b), s.inventory.lowStock(100));
        assertThrows(InvalidDataException.class,
                () -> new ApplicationData(List.of(s.m, s.m), List.of(), List.of(), new Configuration(100)));
    }

    @Test
    void estimatorsAndZeroLossRate() {
        var s = new Business();

        assertEquals(100, s.prints.evaluate("P", "B", new StandardEstimator()).estimation());
        assertEquals(105, s.prints.evaluate("P", "B").estimation());
        s.catalog.update("PLA", properties("PLA", 190, 220, 0));
        assertEquals(100, s.prints.evaluate("P", "B").estimation());
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, 0, -1})
    void invalidEstimationDoesNotMutateAnything(double value) {
        var s = new Business();
        ConsumptionEstimator invalid = (p, m) -> value;
        assertThrows(InvalidQuantityException.class, () -> s.prints.complete("P", "B", 10, invalid));
        assertThrows(InvalidQuantityException.class, () -> s.prints.evaluate("P", "B", invalid));
        assertEquals(1000, s.b.remainingWeight());
        assertEquals(PrintProject.Status.PLANNED, s.p.status());
        assertEquals(0, s.changes);
    }

    @Test
    void estimationOverflow() {
        var s = new Business();
        s.prints.update("P", project("X", s.m, Double.MAX_VALUE, 1.75, 205, 55).parameters());
        assertThrows(InvalidQuantityException.class, () -> s.prints.evaluate("P", "B"));
    }

    @Test
    void moneyAndGlobalRounding() {
        var m = material();
        var b = spool("B", m, 3, 1, "1.00", 1.75);

        assertEquals(new BigDecimal("0.333333333333"), b.pricePerGram());
        assertEquals(new BigDecimal("0.33"), b.cost(1));
        assertEquals(new BigDecimal("0.00"), spool("G", m, 100, 100, "0", 1.75).cost(100));
        var catalog = new CatalogService(List.of(m), () -> { });
        var stock = new InventoryService(List.of(b, spool("C", m, 3, 1, "1.00", 1.75)), catalog, () -> { });

        assertEquals(new BigDecimal("0.67"), stock.remainingValue());
        assertEquals(2, stock.availableWeight());
        assertEquals(new BigDecimal("0.01"), spool("R", m, 1000, 1000, "1.00", 1.75).cost(5));
    }

    @ParameterizedTest
    @CsvSource({"190,0,true", "220,60,true", "189,0,false", "221,60,false", "200,61,false"})
    void inclusiveTemperatures(double nozzle, double bed, boolean expected) {
        var m = material();

        assertEquals(expected, PrintService.compatible(project("P", m, 10, 1.75, nozzle, bed), spool(m)));
    }

    @Test
    void materialAndDiameterCompatibility() {
        var s = new Business();

        assertFalse(PrintService.compatible(s.p, spool("D", s.m, 100, 100, "0", 2.85)));
        var other = new Material("PETG", s.m.properties());

        assertFalse(PrintService.compatible(s.p, spool(other)));
        assertEquals(List.of(s.b), s.prints.compatibleSpools("P"));
    }

    @Test
    void actualCompletionAndHistory() {
        var s = new Business();
        s.b.adjust(101);
        assertFalse(s.prints.evaluate("P", "B").feasible());
        s.prints.complete("P", "B", 100);
        assertEquals(1, s.b.remainingWeight());
        assertEquals(PrintProject.Status.COMPLETED, s.p.status());
        var report = s.p.report();

        assertEquals(105, report.estimation());
        assertEquals(new BigDecimal("2.00"), report.actualCost());
        assertEquals(new BigDecimal("2.10"), report.estimatedCost());
        assertEquals(LocalDateTime.now(CLOCK), report.executionDate());
        s.catalog.update("PLA", properties("Nouveau", 210, 230, 1));
        assertSame(s.m, s.b.material());
        assertSame(s.m, s.p.parameters().material());
        assertSame(report, s.p.report());
        assertEquals("PLA", report.materialLabel());
        assertThrows(InvalidProjectStateException.class, () -> s.prints.complete("P", "B", 1));
        assertThrows(InvalidProjectStateException.class, () -> s.prints.cancel("P"));
        assertThrows(InvalidProjectStateException.class, () -> s.prints.update("P", s.p.parameters()));
        assertEquals(1, s.b.remainingWeight());
    }

    @Test
    void completionRejectionAndCorrection() {
        var s = new Business();

        assertThrows(InsufficientStockException.class, () -> s.prints.complete("P", "B", 1001));
        assertEquals(1000, s.b.remainingWeight());
        assertNull(s.p.report());
        s.catalog.update("PLA", properties("PLA+", 210, 230, .1));
        assertFalse(s.prints.evaluate("P", "B").feasible());
        assertThrows(IncompatibleMaterialException.class, () -> s.prints.complete("P", "B", 100));
        assertEquals(PrintProject.Status.PLANNED, s.p.status());
        s.prints.update("P", project("X", s.m, 10, 1.75, 215, 55).parameters());
        assertTrue(s.prints.evaluate("P", "B").feasible());
        s.prints.cancel("P");
        assertThrows(InvalidProjectStateException.class, () -> s.prints.complete("P", "B", 10));
        assertThrows(InvalidProjectStateException.class, () -> s.prints.update("P", s.p.parameters()));
        assertFalse(s.prints.evaluate("P", "B").feasible());
        assertEquals(1000, s.b.remainingWeight());
    }

    @Test
    void historicalStatisticsIndependentOfWeighings() {
        var s = new Business();
        var statistics = new StatisticsService(s.catalog, s.inventory, s.prints);
        s.prints.complete("P", "B", 80);
        s.inventory.executeOperation(new StockAdjustment("B", 1000));
        assertEquals(1000, statistics.total().availableWeight());
        assertEquals(80, statistics.total().actualConsumption());
        assertEquals(new BigDecimal("1.60"), statistics.total().actualCost());
        assertEquals(statistics.total(), statistics.byMaterial().getFirst().summary());
        s.catalog.update("PLA", properties("PLA+", 190, 220, .9));
        assertEquals(80, statistics.byMaterial().getFirst().summary().actualConsumption());
        assertEquals("PLA", statistics.byMaterial().getFirst().materialId());
    }

    @Test
    void evaluationsDoNotReserveStock() {
        var s = new Business();
        s.prints.create(project("P2", s.m, 950, 1.75, 205, 55));
        assertTrue(s.prints.evaluate("P2", "B").feasible());
        assertTrue(s.prints.evaluate("P", "B").feasible());
        s.prints.complete("P", "B", 100);
        assertFalse(s.prints.evaluate("P2", "B").feasible());
        assertThrows(InsufficientStockException.class, () -> s.prints.complete("P2", "B", 950));
        assertEquals(900, s.b.remainingWeight());
        s.prints.complete("P2", "B", 900);
        assertEquals(0, s.b.remainingWeight());
    }

    @Test
    void dryingAcrossMidnightAndClock() {
        var s = new Business();

        assertEquals(LocalDateTime.of(2025, 5, 10, 2, 0), s.drying.calculateEnd("B", LocalDateTime.of(2025, 5, 9, 22, 0)));
        var now = LocalDateTime.now(CLOCK);
        s.drying.record("B", now);
        assertEquals(now, s.b.lastDrying());
        assertThrows(InvalidDataException.class, () -> s.drying.record("B", now.plusNanos(1)));
        assertEquals(now, s.b.lastDrying());
        assertTrue(s.drying.recommendations("B").contains("45.0 °C"));
    }
}
