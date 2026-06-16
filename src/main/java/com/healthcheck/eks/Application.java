package com.healthcheck.eks;

import com.healthcheck.eks.client.EksKubernetesClientFactory;
import com.healthcheck.eks.config.AppConfig;
import com.healthcheck.eks.model.PodStatusInfo;
import com.healthcheck.eks.model.ServiceStatusInfo;
import com.healthcheck.eks.service.ClusterHealthService;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest;

import java.util.List;

public final class Application {

    public static void main(String[] args) throws Exception {
        AppConfig config = AppConfig.load();

        System.out.printf("Connecting to EKS cluster '%s' in region '%s'%n",
                config.clusterName(), config.awsRegion());
        config.namespace().ifPresent(ns -> System.out.printf("Namespace filter: %s%n", ns));
        printAwsIdentity(config);

        ApiClient apiClient = EksKubernetesClientFactory.create(config);
        ClusterHealthService healthService = new ClusterHealthService(apiClient, config);

        try {
            printPods(healthService.listPods());
            System.out.println();
            printServices(healthService.listServices());
        } catch (RuntimeException e) {
            if (e.getCause() instanceof ApiException apiException && apiException.getCode() == 401) {
                System.err.println();
                System.err.println("Kubernetes API returned 401 Unauthorized.");
                System.err.println("Your AWS credentials work, but this IAM principal is not authorized on the cluster.");
                System.err.println("Grant access with an EKS access entry, for example:");
                System.err.printf("  aws eks create-access-entry --cluster-name %s --principal-arn <your-iam-arn> --type STANDARD --region %s%n",
                        config.clusterName(), config.awsRegion());
                System.err.printf("  aws eks associate-access-policy --cluster-name %s --principal-arn <your-iam-arn> --policy-arn arn:aws:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy --access-scope type=cluster --region %s%n",
                        config.clusterName(), config.awsRegion());
            }
            throw e;
        }
    }

    private static void printPods(List<PodStatusInfo> pods) {
        System.out.println("=== Pods ===");
        if (pods.isEmpty()) {
            System.out.println("No pods found.");
            return;
        }

        System.out.printf("%-20s %-40s %-12s %-8s %-12s%n",
                "NAMESPACE", "NAME", "PHASE", "READY", "CONTAINERS");
        for (PodStatusInfo pod : pods) {
            System.out.printf("%-20s %-40s %-12s %-8s %d/%d%n",
                    pod.namespace(),
                    truncate(pod.name(), 40),
                    pod.phase(),
                    pod.ready() ? "yes" : "no",
                    pod.readyContainers(),
                    pod.totalContainers());
        }
        System.out.printf("%nTotal pods: %d%n", pods.size());
    }

    private static void printServices(List<ServiceStatusInfo> services) {
        System.out.println("=== Services ===");
        if (services.isEmpty()) {
            System.out.println("No services found.");
            return;
        }

        System.out.printf("%-20s %-40s %-15s %-10s %-12s%n",
                "NAMESPACE", "NAME", "TYPE", "ENDPOINTS", "HEALTHY");
        for (ServiceStatusInfo service : services) {
            System.out.printf("%-20s %-40s %-15s %d/%d        %-8s%n",
                    service.namespace(),
                    truncate(service.name(), 40),
                    service.type(),
                    service.readyEndpoints(),
                    service.totalEndpoints(),
                    service.healthy() ? "yes" : "no");
        }
        System.out.printf("%nTotal services: %d%n", services.size());
    }

    private static void printAwsIdentity(AppConfig config) {
        try (StsClient sts = StsClient.builder().region(Region.of(config.awsRegion())).build()) {
            var identity = sts.getCallerIdentity(GetCallerIdentityRequest.builder().build());
            System.out.printf("AWS identity: %s%n", identity.arn());
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 3) + "...";
    }
}
