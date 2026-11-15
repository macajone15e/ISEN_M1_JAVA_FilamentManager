package com.filamentmanager.exception;

public class InsufficientStockException extends InvalidDataException {
    public InsufficientStockException(String message) {
        super(message);
    }
}
