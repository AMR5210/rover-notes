# Deployment

The system runs as four deployables: a Next.js frontend, the Spring Boot modular
monolith, a Python service for parsing and evaluation, and one PostgreSQL instance.

Infrastructure is Terraform-managed on AWS: ECS Fargate for the containers, RDS for
Postgres, S3 for documents, and Secrets Manager for credentials. Kubernetes is not used.
At fewer than ten queries per second, a service mesh and event-driven autoscaling add
operational cost without a corresponding benefit.

Continuous integration builds, tests, runs the evaluation gate, scans images with Trivy,
signs them with cosign, and deploys by digest rather than tag.

Rollback is a single command, and a deliberately introduced retrieval regression is
blocked before it reaches production.
