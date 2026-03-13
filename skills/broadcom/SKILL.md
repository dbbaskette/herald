---
name: broadcom
description: >
  Broadcom/VMware Tanzu knowledge base — answers questions about Tanzu Platform,
  Tanzu Application Service, Spring tooling, and the VMware-to-Broadcom transition.
  Use when asked about Tanzu, TAS, TKG, or Broadcom enterprise products.
---

# Broadcom / VMware Tanzu Knowledge Base

Reference guide for VMware Tanzu products and the Broadcom acquisition context.

## Broadcom–VMware Transition

- **Broadcom completed its acquisition of VMware** on November 22, 2023.
- VMware products are now part of the **Broadcom Software Group**.
- VMware Tanzu products were reorganized under the **Tanzu by Broadcom** brand.
- Licensing shifted from perpetual licenses to **subscription-based** models.
- Many standalone SKUs were consolidated into larger platform bundles.

## Tanzu Product Portfolio

### Tanzu Platform

The unified application platform that brings together build, run, and manage capabilities:

- **Tanzu Platform for Kubernetes** — the core platform for deploying and managing apps on Kubernetes clusters. Includes supply chain automation, service bindings, and developer portal.
- **Tanzu Platform for Cloud Foundry (TAS)** — formerly Tanzu Application Service (TAS). Provides a Cloud Foundry-based PaaS experience with `cf push` workflows, buildpacks, and managed services.

### Tanzu Application Service (TAS)

- Cloud Foundry-based platform for deploying applications with `cf push`.
- Provides built-in routing, logging, scaling, and service marketplace.
- Supports Java, .NET, Node.js, Go, Python, and other languages via buildpacks.
- Key components: Diego (container runtime), Gorouter (HTTP routing), UAA (identity), BOSH (lifecycle management).
- Current major version: **TAS 6.x** (based on cf-deployment).

### Tanzu Kubernetes Grid (TKG)

- Kubernetes distribution for creating and managing conformant Kubernetes clusters.
- Runs on vSphere, AWS, and Azure.
- Uses Cluster API for declarative cluster lifecycle management.
- Includes Pinniped for authentication and Contour for ingress.

### Tanzu Operations Manager (Ops Manager)

- Web UI and API for deploying and managing BOSH-based products (tiles).
- Used to install and configure TAS, TKG-integrated editions, and partner services.
- Being phased out in favor of Tanzu Platform hub-based management.

### Tanzu Spring Products

- **Spring Boot** — opinionated framework for production-ready Spring applications.
- **Spring Cloud** — microservices patterns: config server, service discovery, circuit breakers.
- **Spring Cloud Gateway for Kubernetes** — API gateway for Kubernetes-deployed apps.
- **Tanzu Spring Runtime** — commercial support subscription for Spring Boot and Spring Framework with CVE patches and LTS releases.

## Common Q&A

**Q: What replaced VMware Tanzu Application Platform (TAP)?**
A: TAP capabilities are now part of **Tanzu Platform for Kubernetes**. The build, deploy, and supply-chain features were integrated into the unified Tanzu Platform.

**Q: Is Cloud Foundry still supported?**
A: Yes. Tanzu Application Service (TAS) continues to be supported and developed. TAS 6.x is the current release line.

**Q: What is the difference between TAS and Tanzu Platform for Kubernetes?**
A: TAS provides a Cloud Foundry `cf push` experience with opinionated abstractions. Tanzu Platform for Kubernetes provides a Kubernetes-native experience with more flexibility and control over the underlying infrastructure.

**Q: How do I migrate from TAS to Kubernetes?**
A: Broadcom offers migration tooling and guidance. General approach: containerize apps, replace CF service bindings with Kubernetes service bindings or direct integrations, and adopt Kubernetes manifests or Helm charts. Spring Boot apps often require minimal code changes.

**Q: What happened to Pivotal?**
A: Pivotal was acquired by VMware in 2019. Pivotal Cloud Foundry (PCF) was renamed to Tanzu Application Service (TAS). Pivotal's Spring team continues as part of Broadcom.

**Q: Where do I get support?**
A: Support is available through the [Broadcom Support Portal](https://support.broadcom.com). Tanzu products are listed under the VMware Tanzu category.

## Key URLs

| Resource | URL |
|----------|-----|
| Tanzu Documentation | https://docs.vmware.com/en/VMware-Tanzu/index.html |
| Broadcom Support Portal | https://support.broadcom.com |
| Spring Project | https://spring.io |
| Cloud Foundry Docs | https://docs.cloudfoundry.org |
| Tanzu Network (downloads) | https://network.tanzu.vmware.com |

## Response Guidelines

- When answering questions, cite the specific product name and version when relevant.
- If a product has been renamed or reorganized, mention both the old and new names for clarity.
- For migration questions, provide practical steps rather than just linking to docs.
- If unsure about a specific version or feature detail, say so and suggest checking the official docs.
