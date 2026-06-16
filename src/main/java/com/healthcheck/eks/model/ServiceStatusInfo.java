package com.healthcheck.eks.model;

public record ServiceStatusInfo(
        String namespace,
        String name,
        String type,
        String clusterIp,
        int readyEndpoints,
        int totalEndpoints,
        boolean healthy
) {
}
