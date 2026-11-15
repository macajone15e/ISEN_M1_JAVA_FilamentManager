package com.filamentmanager.model;

import com.filamentmanager.exception.InvalidDataException;

import java.util.*;

public record ApplicationData(List<Material> materials, List<Spool> spools,
                                 List<PrintProject> projects, Configuration configuration) {
    public ApplicationData {
        materials = List.copyOf(Validation.required(materials, "Catalogue"));
        spools = List.copyOf(Validation.required(spools, "Bobines"));
        projects = List.copyOf(Validation.required(projects, "Projets"));
        Validation.required(configuration, "Configuration");

        Map<String, Material> catalog = new HashMap<>();
        for (Material material : materials) {
            if (catalog.putIfAbsent(material.id(), material) != null) {
                throw new InvalidDataException("Matériau dupliqué : " + material.id());
            }
        }

        Map<String, Spool> stock = new HashMap<>();
        for (Spool spool : spools) {
            if (stock.putIfAbsent(spool.id(), spool) != null) {
                throw new InvalidDataException("Bobine dupliquée : " + spool.id());
            }
            if (catalog.get(spool.material().id()) != spool.material()) {
                throw new InvalidDataException("Référence matériau inconnue/non partagée : " + spool.id());
            }
        }

        Set<String> projectIds = new HashSet<>();
        for (PrintProject project : projects) {
            if (!projectIds.add(project.id())) {
                throw new InvalidDataException("Projet dupliqué : " + project.id());
            }
            PrintProject.Parameters parameters = project.parameters();
            if (catalog.get(parameters.material().id()) != parameters.material()) {
                throw new InvalidDataException("Référence matériau projet inconnue/non partagée : " + project.id());
            }
            if (project.status() == PrintProject.Status.COMPLETED) {
                PrintProject.Report report = project.report();
                Spool spool = stock.get(report.spoolId());
                if (spool == null) {
                    throw new InvalidDataException("Bilan incohérent : " + project.id());
                }
                boolean inconsistentMaterial = spool.material() != parameters.material();
                boolean inconsistentDiameter = Double.compare(spool.diameter(), parameters.diameter()) != 0;
                boolean excessiveConsumption = report.actualConsumption() > spool.initialWeight();
                boolean inconsistentEstimatedCost = spool.cost(report.estimation()).compareTo(report.estimatedCost()) != 0;
                boolean inconsistentActualCost = spool.cost(report.actualConsumption()).compareTo(report.actualCost()) != 0;
                if (inconsistentMaterial || inconsistentDiameter || excessiveConsumption
                        || inconsistentEstimatedCost || inconsistentActualCost) {
                    throw new InvalidDataException("Bilan incohérent : " + project.id());
                }
            }
        }
    }
}
