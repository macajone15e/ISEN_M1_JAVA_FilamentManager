package com.filamentmanager.exception;

public class SpoolNotFoundException extends InvalidDataException {
    public SpoolNotFoundException(String id) {
        super("Bobine introuvable : " + id);
    }
}
