package com.filamentmanager.service;

import com.filamentmanager.model.*;
import com.filamentmanager.exception.InvalidDataException;

import java.util.*;

public final class CatalogService {
    private final Map<String, Material> materials = new LinkedHashMap<>();
    private final Runnable onChange;

    public CatalogService(Collection<Material> initialItems, Runnable onChange) {
        this.onChange = onChange;
        for (Material material : initialItems) {
            insert(material);
        }
    }

    private void insert(Material material) {
        Validation.required(material, "Matériau");
        if (materials.putIfAbsent(material.id(), material) != null) {
            throw new InvalidDataException("Matériau dupliqué : " + material.id());
        }
    }

    public void add(Material material) {
        insert(material);
        onChange.run();
    }

    public void update(String id, Material.Properties properties) {
        require(id).update(properties);
        onChange.run();
    }

    public Optional<Material> find(String id) {
        return Optional.ofNullable(materials.get(id));
    }

    public Material require(String id) {
        Material material = materials.get(id);
        if (material == null) {
            throw new InvalidDataException("Matériau inconnu : " + id);
        }
        return material;
    }

    public List<Material> list() {
        return List.copyOf(materials.values());
    }

    public void validateReference(Material material) {
        Validation.required(material, "Matériau");
        // Identity ensures that all objects see catalog updates.
        if (require(material.id()) != material) {
            throw new InvalidDataException("Référence matériau non partagée : " + material.id());
        }
    }
}
