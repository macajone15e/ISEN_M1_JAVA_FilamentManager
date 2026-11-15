package com.filamentmanager.model;

import com.filamentmanager.exception.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;

public final class Spool {
    private final String id;
    private final Material material;
    private final String brand;
    private final String color;
    private final String range;
    private final double initialWeight;
    private final double diameter;
    private double remainingWeight;
    private final BigDecimal price;
    private final LocalDate purchaseDate;
    private LocalDateTime lastDrying;

    public Spool(String id, Material material, String brand, String color, String range,
                  double initialWeight, double remainingWeight, double diameter, BigDecimal price,
                  LocalDate purchaseDate, LocalDateTime lastDrying, Clock clock) {
        this.id = Validation.text(id, "Identifiant bobine");
        this.material = Validation.required(material, "Matériau");
        this.brand = Validation.text(brand, "Marque");
        this.color = Validation.text(color, "Couleur");
        if (range == null) {
            this.range = "";
        } else {
            this.range = range.strip();
        }
        this.initialWeight = Validation.positive(initialWeight, "Poids initial");
        this.diameter = Validation.positive(diameter, "Diamètre");
        this.price = Validation.amount(price, "Prix d'achat");
        this.purchaseDate = Validation.required(purchaseDate, "Date achat");
        if (purchaseDate.isAfter(LocalDate.now(clock))) {
            throw new InvalidDataException("Date d'achat future : " + id);
        }
        adjust(remainingWeight);
        if (lastDrying != null) {
            recordDrying(lastDrying, clock);
        }
    }

    public void checkConsumption(double quantity) {
        Validation.positive(quantity, "Consommation");
        if (quantity > remainingWeight) {
            throw new InsufficientStockException("Stock insuffisant : " + id + " (" + remainingWeight + " g)");
        }
    }

    public void consume(double quantity) {
        checkConsumption(quantity);
        remainingWeight -= quantity;
    }

    public void checkAdjustment(double measurement) {
        Validation.nonNegative(measurement, "Mesure");
        if (measurement > initialWeight) {
            throw new InvalidQuantityException("Mesure supérieure à la capacité : " + id);
        }
    }

    public void adjust(double measurement) {
        checkAdjustment(measurement);
        remainingWeight = measurement;
    }

    public void recordDrying(LocalDateTime end, Clock clock) {
        Validation.required(end, "Fin séchage");
        if (end.isAfter(LocalDateTime.now(clock))) {
            throw new InvalidDataException("Fin de séchage future : " + id);
        }
        lastDrying = end;
    }

    public BigDecimal pricePerGram() {
        return price.divide(BigDecimal.valueOf(initialWeight), 12, RoundingMode.HALF_UP);
    }

    public BigDecimal unroundedCost(double weight) {
        BigDecimal unitPrice = pricePerGram();
        double validWeight = Validation.nonNegative(weight, "Poids");
        return unitPrice.multiply(BigDecimal.valueOf(validWeight));
    }

    public BigDecimal cost(double weight) {
        return unroundedCost(weight).setScale(2, RoundingMode.HALF_UP);
    }

    public String id() {
        return id;
    }

    public Material material() {
        return material;
    }

    public String brand() {
        return brand;
    }

    public String color() {
        return color;
    }

    public String range() {
        return range;
    }

    public double initialWeight() {
        return initialWeight;
    }

    public double remainingWeight() {
        return remainingWeight;
    }

    public double diameter() {
        return diameter;
    }

    public BigDecimal price() {
        return price;
    }

    public LocalDate purchaseDate() {
        return purchaseDate;
    }

    public LocalDateTime lastDrying() {
        return lastDrying;
    }

    @Override
    public String toString() {
        return id + " — " + material.properties().name() + " " + brand + " " + color + " " + range
                + " : " + remainingWeight + "/" + initialWeight + " g, " + diameter + " mm, valeur " + cost(remainingWeight) + " EUR, séchage : " + lastDrying;
    }
}
