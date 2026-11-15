package com.filamentmanager.repository;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.filamentmanager.model.*;

import java.math.BigDecimal;
import java.time.*;

/** Explicit JSON format, without polymorphic serialization or annotations in the domain model. */
final class PersistenceDto {
    private PersistenceDto() {
    }

    record MaterialDto(
            @JsonProperty(required = true) String id,
            @JsonProperty(required = true) PropertiesDto properties) {
    }

    record SpoolDto(
            @JsonProperty(required = true) String id,
            @JsonProperty(required = true) String materialId,
            @JsonProperty(required = true) String brand,
            @JsonProperty(required = true) String color,
            String range,
            @JsonProperty(required = true) double initialWeight,
            @JsonProperty(required = true) double remainingWeight,
            @JsonProperty(required = true) double diameter,
            @JsonProperty(required = true) BigDecimal price,
            @JsonProperty(required = true) LocalDate purchaseDate,
            LocalDateTime lastDrying) {
    }

    record ParametersDto(
            @JsonProperty(required = true) String name,
            @JsonProperty(required = true) String materialId,
            @JsonProperty(required = true) double diameter,
            @JsonProperty(required = true) double slicerWeight,
            @JsonProperty(required = true) double nozzleTemperature,
            @JsonProperty(required = true) double bedTemperature) {
    }

    record ProjectDto(
            @JsonProperty(required = true) String id,
            @JsonProperty(required = true) ParametersDto parameters,
            @JsonProperty(required = true) PrintProject.Status status,
            ReportDto report) {
    }

    record ReportDto(
            @JsonProperty(required = true) String spoolId,
            @JsonProperty(required = true) double actualConsumption,
            @JsonProperty(required = true) double estimation,
            @JsonProperty(required = true) String estimator,
            @JsonProperty(required = true) BigDecimal estimatedCost,
            @JsonProperty(required = true) BigDecimal actualCost,
            @JsonProperty(required = true) LocalDateTime executionDate,
            @JsonProperty(required = true) String materialLabel) {
    }

    record ConfigurationDto(@JsonProperty(required = true) double lowStockThreshold) {
    }

    // Boxed values preserve nulls so domain validation can reject them instead of accepting zero.
    record PropertiesDto(
            @JsonProperty(required = true) String name,
            @JsonProperty(required = true) Double nozzleMin,
            @JsonProperty(required = true) Double nozzleMax,
            @JsonProperty(required = true) Double bedMin,
            @JsonProperty(required = true) Double bedMax,
            @JsonProperty(required = true) Double dryingTemperature,
            @JsonProperty(required = true) Double dryingDuration,
            @JsonProperty(required = true) Double density,
            @JsonProperty(required = true) Double lossRate) {
    }
}
