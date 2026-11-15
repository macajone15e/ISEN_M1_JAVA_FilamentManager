package com.filamentmanager.model;

import com.filamentmanager.exception.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public final class PrintProject {
    public enum Status {
        PLANNED("PLANIFIE"),
        COMPLETED("TERMINE"),
        CANCELLED("ANNULE");

        private final String displayLabel;

        Status(String displayLabel) {
            this.displayLabel = displayLabel;
        }

        @Override
        public String toString() {
            return displayLabel;
        }
    }

    public record Parameters(String name, Material material, double diameter, double slicerWeight,
                             double nozzleTemperature, double bedTemperature) {
        public Parameters {
            name = Validation.text(name, "Nom projet");
            Validation.required(material, "Matériau projet");
            Validation.positive(diameter, "Diamètre requis");
            Validation.positive(slicerWeight, "Quantité slicer");
            Validation.nonNegative(nozzleTemperature, "Température buse");
            Validation.nonNegative(bedTemperature, "Température plateau");
        }

        @Override
        public String toString() {
            return "Paramètres[nom=" + name + ", matériau=" + material + ", diamètre=" + diameter
                    + ", poids slicer=" + slicerWeight + ", température buse=" + nozzleTemperature
                    + ", température plateau=" + bedTemperature + "]";
        }
    }

    // Historical values are snapshots: later catalog edits must not rewrite completed prints.
    public record Report(String spoolId, double actualConsumption, double estimation, String estimator,
                        BigDecimal estimatedCost, BigDecimal actualCost, LocalDateTime executionDate, String materialLabel) {
        public Report {
            spoolId = Validation.text(spoolId, "Bobine du bilan");
            Validation.positive(actualConsumption, "Consommation réelle");
            Validation.positive(estimation, "Estimation");
            estimator = Validation.text(estimator, "Estimateur");
            Validation.amount(estimatedCost, "Coût estimé");
            Validation.amount(actualCost, "Coût réel");
            Validation.required(executionDate, "Date exécution");
            materialLabel = Validation.text(materialLabel, "Libellé historique");
        }

        @Override
        public String toString() {
            String estimatorLabel = switch (estimator) {
                case "StandardEstimator" -> "standard";
                case "LossAdjustedEstimator" -> "avec pertes";
                default -> estimator;
            };
            return "Bilan[bobine=" + spoolId + ", consommation réelle=" + actualConsumption
                    + ", estimation=" + estimation + ", estimateur=" + estimatorLabel
                    + ", coût estimé=" + estimatedCost + ", coût réel=" + actualCost
                    + ", date exécution=" + executionDate + ", matériau=" + materialLabel + "]";
        }
    }

    private final String id;
    private Parameters parameters;
    private Status status = Status.PLANNED;
    private Report report;

    public PrintProject(String id, Parameters parameters) {
        this.id = Validation.text(id, "Identifiant projet");
        this.parameters = Validation.required(parameters, "Paramètres projet");
    }

    // Completion and cancellation are final; neither state can be edited or consumed again.
    public void checkPlanned() {
        if (status != Status.PLANNED) {
            throw new InvalidProjectStateException("Projet " + id + " : statut " + status);
        }
    }

    public void update(Parameters newParameters) {
        checkPlanned();
        parameters = Validation.required(newParameters, "Paramètres projet");
    }

    public void cancel() {
        checkPlanned();
        status = Status.CANCELLED;
    }

    public void complete(Report result) {
        checkPlanned();
        report = Validation.required(result, "Bilan");
        status = Status.COMPLETED;
    }

    public String id() {
        return id;
    }

    public Parameters parameters() {
        return parameters;
    }

    public Status status() {
        return status;
    }

    public Report report() {
        return report;
    }

    @Override
    public String toString() {
        String description = id + " — " + parameters.name() + " : " + status + " " + parameters;
        if (report != null) {
            description += "\n  " + report;
        }
        return description;
    }
}
