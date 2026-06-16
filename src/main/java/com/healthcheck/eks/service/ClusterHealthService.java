package com.healthcheck.eks.service;

import com.healthcheck.eks.config.AppConfig;
import com.healthcheck.eks.model.PodStatusInfo;
import com.healthcheck.eks.model.ServiceStatusInfo;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1ContainerStatus;
import io.kubernetes.client.openapi.models.V1EndpointSubset;
import io.kubernetes.client.openapi.models.V1Endpoints;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.openapi.models.V1ServiceList;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ClusterHealthService {

    private final CoreV1Api coreApi;
    private final Optional<String> namespaceFilter;

    public ClusterHealthService(ApiClient apiClient, AppConfig config) {
        this.coreApi = new CoreV1Api(apiClient);
        this.namespaceFilter = config.namespace();
    }

    public List<PodStatusInfo> listPods() throws ApiException {
        V1PodList podList = namespaceFilter
                .map(ns -> listPodsInNamespace(ns))
                .orElseGet(this::listPodsInAllNamespaces);

        List<PodStatusInfo> pods = new ArrayList<>();
        for (V1Pod pod : podList.getItems()) {
            pods.add(toPodStatus(pod));
        }
        return pods;
    }

    public List<ServiceStatusInfo> listServices() throws ApiException {
        V1ServiceList serviceList = namespaceFilter
                .map(ns -> listServicesInNamespace(ns))
                .orElseGet(this::listServicesInAllNamespaces);

        List<ServiceStatusInfo> services = new ArrayList<>();
        for (V1Service service : serviceList.getItems()) {
            services.add(toServiceStatus(service));
        }
        return services;
    }

    private V1PodList listPodsInNamespace(String namespace) {
        try {
            return coreApi.listNamespacedPod(namespace).execute();
        } catch (ApiException e) {
            throw new RuntimeException("Failed to list pods in namespace " + namespace, e);
        }
    }

    private V1PodList listPodsInAllNamespaces() {
        try {
            return coreApi.listPodForAllNamespaces().execute();
        } catch (ApiException e) {
            throw new RuntimeException("Failed to list pods in all namespaces", e);
        }
    }

    private V1ServiceList listServicesInNamespace(String namespace) {
        try {
            return coreApi.listNamespacedService(namespace).execute();
        } catch (ApiException e) {
            throw new RuntimeException("Failed to list services in namespace " + namespace, e);
        }
    }

    private V1ServiceList listServicesInAllNamespaces() {
        try {
            return coreApi.listServiceForAllNamespaces().execute();
        } catch (ApiException e) {
            throw new RuntimeException("Failed to list services in all namespaces", e);
        }
    }

    private PodStatusInfo toPodStatus(V1Pod pod) {
        String namespace = pod.getMetadata().getNamespace();
        String name = pod.getMetadata().getName();
        String phase = pod.getStatus() != null && pod.getStatus().getPhase() != null
                ? pod.getStatus().getPhase()
                : "Unknown";

        int readyContainers = 0;
        int totalContainers = 0;
        if (pod.getStatus() != null && pod.getStatus().getContainerStatuses() != null) {
            for (V1ContainerStatus status : pod.getStatus().getContainerStatuses()) {
                totalContainers++;
                if (Boolean.TRUE.equals(status.getReady())) {
                    readyContainers++;
                }
            }
        }

        boolean ready = "Running".equals(phase)
                && totalContainers > 0
                && readyContainers == totalContainers;

        String node = pod.getSpec() != null ? pod.getSpec().getNodeName() : null;

        return new PodStatusInfo(namespace, name, phase, ready, readyContainers, totalContainers, node);
    }

    private ServiceStatusInfo toServiceStatus(V1Service service) throws ApiException {
        String namespace = service.getMetadata().getNamespace();
        String name = service.getMetadata().getName();
        String type = service.getSpec() != null && service.getSpec().getType() != null
                ? service.getSpec().getType()
                : "Unknown";
        String clusterIp = service.getSpec() != null ? service.getSpec().getClusterIP() : null;

        EndpointCounts counts = countEndpoints(namespace, name);

        boolean healthy = isServiceHealthy(type, clusterIp, counts);

        return new ServiceStatusInfo(
                namespace,
                name,
                type,
                clusterIp,
                counts.ready(),
                counts.total(),
                healthy
        );
    }

    private boolean isServiceHealthy(String type, String clusterIp, EndpointCounts counts) {
        if ("ExternalName".equals(type)) {
            return true;
        }
        if ("ClusterIP".equals(type) && ("None".equals(clusterIp) || clusterIp == null)) {
            return true;
        }
        return counts.total() > 0 && counts.ready() == counts.total();
    }

    private EndpointCounts countEndpoints(String namespace, String serviceName) throws ApiException {
        V1Endpoints endpoints;
        try {
            endpoints = coreApi.readNamespacedEndpoints(serviceName, namespace).execute();
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                return new EndpointCounts(0, 0);
            }
            throw e;
        }

        int ready = 0;
        int total = 0;

        if (endpoints.getSubsets() != null) {
            for (V1EndpointSubset subset : endpoints.getSubsets()) {
                if (subset.getAddresses() != null) {
                    ready += subset.getAddresses().size();
                    total += subset.getAddresses().size();
                }
                if (subset.getNotReadyAddresses() != null) {
                    total += subset.getNotReadyAddresses().size();
                }
            }
        }

        return new EndpointCounts(ready, total);
    }

    private record EndpointCounts(int ready, int total) {
    }
}
