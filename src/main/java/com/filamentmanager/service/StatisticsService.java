package com.filamentmanager.service;

import com.filamentmanager.model.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.DoubleSummaryStatistics;
import java.util.List;

public final class StatisticsService {
    public record Summary(double availableWeight, BigDecimal remainingValue, double actualConsumption, BigDecimal actualCost) {
        @Override
        public String toString() {
            return "Résumé[poids disponible=" + availableWeight + ", valeur restante=" + remainingValue
                    + ", consommation réelle=" + actualConsumption + ", coût réel=" + actualCost + "]";
        }
    }

    public record ByMaterial(String materialId, Summary summary) {
        @Override
        public String toString() {
            return "Par matériau[matériau=" + materialId + ", résumé=" + summary + "]";
        }
    }

    private final CatalogService catalog;
    private final InventoryService inventory;
    private final PrintService prints;

    public StatisticsService(CatalogService catalog, InventoryService inventory, PrintService prints) {
        this.catalog = catalog;
        this.inventory = inventory;
        this.prints = prints;
    }

    private Summary summarize(List<Spool> spools, List<PrintProject> projects) {
        DoubleSummaryStatistics consumptions = new DoubleSummaryStatistics();
        BigDecimal totalCost = new BigDecimal("0.00");
        for (PrintProject project : projects) {
            // Planned or cancelled projects have not consumed any stock.
            if (project.status() == PrintProject.Status.COMPLETED) {
                PrintProject.Report report = project.report();
                consumptions.accept(report.actualConsumption());
                totalCost = totalCost.add(report.actualCost());
            }
        }
        double totalConsumption = Validation.nonNegative(consumptions.getSum(), "Consommation totale");
        double availableWeight = InventoryService.totalWeight(spools);
        BigDecimal remainingValue = InventoryService.value(spools);
        return new Summary(availableWeight, remainingValue, totalConsumption, totalCost);
    }

    public Summary total() {
        return summarize(inventory.list(), prints.list());
    }

    public List<ByMaterial> byMaterial() {
        List<ByMaterial> result = new ArrayList<>();
        for (Material material : catalog.list()) {
            List<Spool> materialSpools = new ArrayList<>();
            for (Spool spool : inventory.list()) {
                if (spool.material().id().equals(material.id())) {
                    materialSpools.add(spool);
                }
            }

            List<PrintProject> materialProjects = new ArrayList<>();
            for (PrintProject project : prints.list()) {
                if (project.parameters().material().id().equals(material.id())) {
                    materialProjects.add(project);
                }
            }
            Summary summary = summarize(materialSpools, materialProjects);
            result.add(new ByMaterial(material.id(), summary));
        }
        return List.copyOf(result);
    }
}
