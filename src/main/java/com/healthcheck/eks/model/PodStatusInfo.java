package com.healthcheck.eks.model;

public record PodStatusInfo(
        String namespace,
        String name,
        String phase,
        boolean ready,
        int readyContainers,
        int totalContainers,
        String node
) {
}
