# Kubernetes manifests

| File | What it runs |
|---|---|
| `app.yaml` | Ledgerline: 2 replicas, probes, resource requests/limits, non-root read-only container, Service, PodDisruptionBudget |
| `postgres.yaml` | Single-instance PostgreSQL (StatefulSet + 1 Gi volume) |
| `kafka.yaml` | Single-node Kafka in KRaft mode |
| `smoke-test.sh` | End-to-end check of a deployment |

CI deploys these to a throwaway [kind](https://kind.sigs.k8s.io/) cluster on every push and
runs `smoke-test.sh` before publishing the image.

## Secrets are not in Git

Create them first, with values you generate:

```bash
kubectl apply -f k8s/namespace.yaml
export JWT_SECRET=$(openssl rand -hex 32)
kubectl -n ledgerline create secret generic ledgerline-secrets \
  --from-literal=db-password="$(openssl rand -hex 16)" \
  --from-literal=jwt-secret="$JWT_SECRET"
```

## Run it locally with kind (optional)

Needs about 5 GB of free disk. Install with `brew install kind jq`.

```bash
kind create cluster --name ledgerline
docker build -t ghcr.io/snehalvs30/ledgerline:latest .
kind load docker-image ghcr.io/snehalvs30/ledgerline:latest --name ledgerline
# create the secret (above), then:
kubectl apply -k k8s/
./k8s/smoke-test.sh
# when finished:
kind delete cluster --name ledgerline
```

## What's simplified for a demo

PostgreSQL and Kafka run as single pods. In production you would use managed services
(RDS / Cloud SQL, MSK / Confluent) or operators (CloudNativePG, Strimzi), keep secrets in a
secret manager (External Secrets, Vault), and validate tokens from an identity provider.