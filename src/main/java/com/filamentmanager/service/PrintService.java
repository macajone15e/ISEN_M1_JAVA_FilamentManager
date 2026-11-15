package com.filamentmanager.service;

import com.filamentmanager.model.*;
import com.filamentmanager.estimator.ConsumptionEstimator;
import com.filamentmanager.operation.StockConsumption;
import com.filamentmanager.exception.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class PrintService {
    public record Evaluation(boolean compatible, boolean feasible, double estimation, BigDecimal estimatedCost) {
        @Override
        public String toString() {
            return "Évaluation[compatible=" + compatible + ", faisable=" + feasible
                    + ", estimation=" + estimation + ", coût estimé=" + estimatedCost + "]";
        }
    }

    private final Map<String, PrintProject> projects = new LinkedHashMap<>();
    private final CatalogService catalog;
    private final InventoryService inventory;
    private final ConsumptionEstimator estimator;
    private final Clock clock;
    private final Runnable onChange;

    public PrintService(Collection<PrintProject> initialItems, CatalogService catalog,
                        InventoryService inventory, ConsumptionEstimator estimator, Clock clock, Runnable onChange) {
        this.catalog = catalog;
        this.inventory = inventory;
        this.estimator = estimator;
        this.clock = clock;
        this.onChange = onChange;
        for (PrintProject project : initialItems) {
            insert(project);
        }
    }

    private void insert(PrintProject project) {
        Validation.required(project, "Projet");
        catalog.validateReference(project.parameters().material());
        if (projects.putIfAbsent(project.id(), project) != null) {
            throw new InvalidDataException("Projet dupliqué : " + project.id());
        }
    }

    public void create(PrintProject project) {
        Validation.required(project, "Projet").checkPlanned();
        insert(project);
        onChange.run();
    }

    public void update(String id, PrintProject.Parameters newParameters) {
        Validation.required(newParameters, "Paramètres");
        catalog.validateReference(newParameters.material());
        require(id).update(newParameters);
        onChange.run();
    }

    public void cancel(String id) {
        require(id).cancel();
        onChange.run();
    }

    public Optional<PrintProject> find(String id) {
        return Optional.ofNullable(projects.get(id));
    }

    public PrintProject require(String id) {
        PrintProject project = projects.get(id);
        if (project == null) {
            throw new InvalidDataException("Projet inconnu : " + id);
        }
        return project;
    }

    public List<PrintProject> list() {
        return List.copyOf(projects.values());
    }

    public static boolean compatible(PrintProject project, Spool spool) {
        PrintProject.Parameters parameters = project.parameters();
        Material.Properties properties = parameters.material().properties();
        boolean sameMaterial = parameters.material().id().equals(spool.material().id());
        boolean sameDiameter = Double.compare(parameters.diameter(), spool.diameter()) == 0;
        boolean nozzleCompatible = parameters.nozzleTemperature() >= properties.nozzleMin()
                && parameters.nozzleTemperature() <= properties.nozzleMax();
        boolean bedCompatible = parameters.bedTemperature() >= properties.bedMin()
                && parameters.bedTemperature() <= properties.bedMax();
        return sameMaterial && sameDiameter && nozzleCompatible && bedCompatible;
    }

    public List<Spool> compatibleSpools(String id) {
        PrintProject project = require(id);
        List<Spool> result = new ArrayList<>();
        for (Spool spool : inventory.list()) {
            if (compatible(project, spool)) {
                result.add(spool);
            }
        }
        return List.copyOf(result);
    }

    private double estimate(PrintProject project, ConsumptionEstimator strategy) {
        Validation.required(strategy, "Estimateur");
        double estimation = strategy.estimate(project, project.parameters().material());
        return Validation.positive(estimation, "Résultat estimation");
    }

    public Evaluation evaluate(String projectId, String spoolId) {
        return evaluate(projectId, spoolId, estimator);
    }

    public Evaluation evaluate(String projectId, String spoolId, ConsumptionEstimator strategy) {
        PrintProject project = require(projectId);
        Spool spool = inventory.require(spoolId);
        double estimation = estimate(project, strategy);
        boolean compatible = compatible(project, spool);
        // Evaluation is advisory: it never reserves stock for this project.
        boolean feasible = project.status() == PrintProject.Status.PLANNED
                && compatible && spool.remainingWeight() >= estimation;
        return new Evaluation(compatible, feasible, estimation, spool.cost(estimation));
    }

    public void complete(String projectId, String spoolId, double actualConsumption) {
        complete(projectId, spoolId, actualConsumption, estimator);
    }

    public void complete(String projectId, String spoolId, double actualConsumption, ConsumptionEstimator strategy) {
        PrintProject project = require(projectId);
        project.checkPlanned();
        Spool spool = inventory.require(spoolId);
        if (!compatible(project, spool)) {
            throw new IncompatibleMaterialException("Projet " + projectId + " incompatible avec bobine " + spoolId);
        }
        // Actual consumption may be below the estimate; only the measured amount leaves stock.
        spool.checkConsumption(actualConsumption);
        double estimation = estimate(project, strategy);

        // Compute everything that can fail before either domain mutation.
        PrintProject.Report report = new PrintProject.Report(spool.id(), actualConsumption, estimation,
                strategy.type(), spool.cost(estimation), spool.cost(actualConsumption),
                LocalDateTime.now(clock), project.parameters().material().properties().name());
        StockConsumption operation = new StockConsumption(spoolId, actualConsumption);
        operation.validate(spool);
        operation.execute(spool);
        project.complete(report);
        onChange.run();
    }
}
