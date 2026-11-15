package com.filamentmanager.model;

import com.filamentmanager.exception.InvalidDataException;

public final class Material {
    /** Immutable properties: updates are fully validated before being applied. Duration is in hours. */
    public record Properties(String name, double nozzleMin, double nozzleMax, double bedMin,
                             double bedMax, double dryingTemperature, double dryingDuration,
                             double density, double lossRate) {
        public Properties {
            name = Validation.text(name, "Nom matériau");
            Validation.nonNegative(nozzleMin, "Buse minimum");
            Validation.nonNegative(nozzleMax, "Buse maximum");
            Validation.nonNegative(bedMin, "Plateau minimum");
            Validation.nonNegative(bedMax, "Plateau maximum");
            Validation.nonNegative(dryingTemperature, "Température séchage");
            Validation.positive(dryingDuration, "Durée séchage");
            Validation.positive(density, "Densité");
            Validation.nonNegative(lossRate, "Taux perte");
            if (nozzleMin > nozzleMax || bedMin > bedMax || lossRate > 1) {
                throw new InvalidDataException("Plages de températures ou taux de perte incohérents");
            }
            if (dryingDuration * 3_600_000_000_000d > Long.MAX_VALUE) {
                throw new InvalidDataException("Durée de séchage trop grande");
            }
        }

        @Override
        public String toString() {
            return "Propriétés[nom=" + name + ", buse min=" + nozzleMin + ", buse max=" + nozzleMax
                    + ", plateau min=" + bedMin + ", plateau max=" + bedMax
                    + ", température séchage=" + dryingTemperature + ", durée séchage=" + dryingDuration
                    + ", densité=" + density + ", taux de perte=" + lossRate + "]";
        }
    }

    private final String id;
    private Properties properties;

    public Material(String id, Properties properties) {
        this.id = Validation.text(id, "Identifiant matériau");
        this.properties = Validation.required(properties, "Propriétés matériau");
    }

    public String id() {
        return id;
    }

    public Properties properties() {
        return properties;
    }

    public void update(Properties newProperties) {
        properties = Validation.required(newProperties, "Propriétés matériau");
    }

    @Override
    public String toString() {
        return id + " — " + properties;
    }
}
