package com.filamentmanager.estimator;

import com.filamentmanager.model.*;

public final class StandardEstimator implements ConsumptionEstimator {
    @Override
    public double estimate(PrintProject project, Material material) {
        return project.parameters().slicerWeight();
    }
}
