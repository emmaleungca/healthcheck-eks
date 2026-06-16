healthcheck-eks
===============

Small CLI utility that connects to an AWS EKS cluster and prints a summary of pods and services.

This README explains how to configure, build, run and debug the application, including steps to diagnose a Kubernetes API 401 Unauthorized when using EKS authentication.

Quick overview
--------------
- Language: Java
- Build: Maven (wrapper included)
- Main class: com.healthcheck.eks.Application

Requirements
------------
- JDK 17+ (or the JDK version used by the project)
- Maven (the repository contains the Maven wrapper `mvnw` so an installed Maven is optional)
- AWS CLI v2 (for `aws eks get-token`) — recommended for token debugging
- kubectl (optional, for inspecting `aws-auth` ConfigMap)
- Network access to the EKS control plane (private endpoint requires VPN/VPC access)

Configuration
-------------
The app reads configuration from `src/main/resources/application.properties` on the classpath and supports overriding via environment variables.

Keys (and precedence):
- aws.region (or env `AWS_REGION` / `AWS_DEFAULT_REGION`) — required
- eks.cluster.name (or env `EKS_CLUSTER_NAME`) — required
- kubernetes.namespace (or env `KUBERNETES_NAMESPACE`) — optional; empty means all namespaces

Example `src/main/resources/application.properties` (do NOT put secrets here):

```ini
# AWS / EKS
aws.region=us-east-1
eks.cluster.name=easy-el

# Kubernetes scope (leave empty for all namespaces)
kubernetes.namespace=
```

How the app gets AWS credentials
--------------------------------
The application uses the AWS SDK's DefaultCredentialsProvider. Provide credentials by one of these safe methods:

1. Environment variables (temporary for a run):
   - `AWS_ACCESS_KEY_ID`
   - `AWS_SECRET_ACCESS_KEY`
   - `AWS_SESSION_TOKEN` (if using temporary credentials)

2. AWS shared credentials file (`~/.aws/credentials`) and optionally `AWS_PROFILE`.

3. IAM role (preferred in production): attach an instance profile / task role / pod IAM role so no secrets are used.

DO NOT commit long-lived credentials to source control. If credentials are ever exposed, rotate them immediately.

Build & run
-----------
From the project root:

Build the project:

```bash
./mvnw -DskipTests package
```

Run the fat JAR:

```bash
java -jar target/healthcheck-eks-1.0.0-SNAPSHOT.jar
```

Running in IntelliJ: open the project, run the `Application` main class. Ensure the Run configuration has the environment variables (if you rely on env vars).

Debugging authentication (401 Unauthorized)
------------------------------------------
A 401 from the Kubernetes API means the API server did not accept the request as authenticated. The most useful first step is to determine whether the problem is:

A) the Java client is not attaching the Bearer token to requests, or
B) the token is being sent but EKS does not recognize the IAM principal (missing `aws-auth` mapping).

1) Reproduce a token and test it directly with curl (recommended):

```bash
REGION=us-east-1
CLUSTER=easy-el

ENDPOINT=$(aws eks describe-cluster --region "$REGION" --name "$CLUSTER" --query "cluster.endpoint" --output text)
aws eks describe-cluster --region "$REGION" --name "$CLUSTER" \
  --query "cluster.certificateAuthority.data" --output text | base64 --decode > ca.crt

# requires AWS CLI v2
TOKEN=$(aws eks get-token --region "$REGION" --cluster-name "$CLUSTER" --output text --query 'status.token')

# should return cluster version (quick test)
curl -i --cacert ca.crt -H "Authorization: Bearer $TOKEN" "https://${ENDPOINT}/version"

# test list pods (may return 403 if authenticated but not authorized)
curl -i --cacert ca.crt -H "Authorization: Bearer $TOKEN" "https://${ENDPOINT}/api/v1/pods"
```

Interpretation:
- curl /version -> 200 OK: the token is valid and accepted by the apiserver; the problem is likely client-side.
- curl -> 401: the token is rejected by EKS. Run `aws sts get-caller-identity` to see which IAM ARN was used and ensure that ARN is mapped in the cluster `aws-auth` ConfigMap (see below).
- curl -> 403: token accepted but RBAC denies access (you are authenticated). Map or bind the IAM principal to appropriate Kubernetes RBAC if you need access.

2) Inspect `aws-auth` mapping (if you have cluster-admin access):

```bash
kubectl -n kube-system get configmap aws-auth -o yaml
```

Look for `mapRoles` and `mapUsers` entries. Your IAM role/user ARN (as returned by `aws sts get-caller-identity`) must be present and mapped to a Kubernetes username/groups.

3) Java client debug output (project contains a temporary debug change):

- The code in `EksKubernetesClientFactory` currently enables the Kubernetes ApiClient HTTP debugging (`client.setDebugging(true);`) to print outgoing requests and responses. Run the app and inspect stdout for the Authorization header and the HTTP response from the API server.
- If the request printed by the Java client contains no Authorization header or the header is malformed, the token is not being attached by the client. We can then either call the token-generation utility in this project or set the bearer token explicitly on the ApiClient before returning it.

Temporary helper in this repo
-----------------------------
This project includes `EksTokenProvider` (in `src/main/java/com/healthcheck/eks/auth/EksTokenProvider.java`) which implements a compatible token generator similar to `aws eks get-token`. If you need an in-Java token generator (for attaching token manually), this class is provided.

Security
--------
- Never commit AWS credentials or other secrets to source control. If you accidentally commit a secret, rotate it immediately.
- For short-lived diagnostic debugging you may enable client HTTP logging in code, but remove the debugging code before committing final changes.
- Prefer IAM roles for production deployments to avoid handling secrets.

If credentials were exposed (IMPORTANT)
--------------------------------------
1. Immediately rotate/delete the exposed access key in the AWS Console (IAM > Users > Security credentials) or via the CLI.
2. Remove the secrets from the repo and any CI/CD systems.
3. If the secret was committed to Git history, rotate the keys and consider using `git filter-repo` / BFG to permanently remove them from history.

Development notes
-----------------
- The `AppConfig` class reads `application.properties` and environment variables. If region or cluster name are missing the app will fail to start with an explanatory message.
- The app uses AWS SDK DefaultCredentialsProvider; ensure the intended credentials are available to the JVM process.

Common commands
---------------
- Build: `./mvnw -DskipTests package`
- Run: `java -jar target/healthcheck-eks-1.0.0-SNAPSHOT.jar`
- Check AWS identity: `aws sts get-caller-identity`
- Get cluster endpoint & CA: `aws eks describe-cluster --region $REGION --name $CLUSTER --query 'cluster.{endpoint:endpoint,ca:certificateAuthority.data}' --output json`

Want me to:
- Add a one-time change to always attach a generated token to the ApiClient (temporary) so you can confirm whether the 401 goes away? Reply "attach token in code" and I will add that patch.
- Help craft an `aws-auth` mapping for your IAM ARN? Reply with the ARN from `aws sts get-caller-identity` and I will provide the exact `kubectl` or `eksctl` command snippets.

License
-------
MIT (adjust as appropriate)

# healthcheck-eks
