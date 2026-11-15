package com.filamentmanager.model;

import com.filamentmanager.exception.InvalidDataException;
import com.filamentmanager.exception.InvalidQuantityException;

import java.math.BigDecimal;

public final class Validation {
    private Validation() { }

    public static String text(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidDataException(field + " obligatoire");
        }
        return value.strip();
    }

    public static double positive(double value, String field) {
        if (!Double.isFinite(value) || value <= 0) {
            throw new InvalidQuantityException(field + " doit être fini et strictement positif");
        }
        return value;
    }

    public static double nonNegative(double value, String field) {
        if (!Double.isFinite(value) || value < 0) {
            throw new InvalidQuantityException(field + " doit être fini et non négatif");
        }
        return value;
    }

    public static <T> T required(T value, String field) {
        if (value == null) {
            throw new InvalidDataException(field + " obligatoire");
        }
        return value;
    }

    public static BigDecimal amount(BigDecimal value, String field) {
        required(value, field);
        if (value.signum() < 0 || value.scale() > 2) {
            throw new InvalidDataException(field + " : montant positif ou nul, deux décimales maximum");
        }
        return value;
    }
}
