package com.filamentmanager.estimator;

import com.filamentmanager.model.*;

@FunctionalInterface
public interface ConsumptionEstimator {
    double estimate(PrintProject project, Material material);
    default String type() {
        return getClass().getSimpleName();
    }
}
