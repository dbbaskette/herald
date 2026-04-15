---
name: system-info
description: >
  Gather system information about the host machine — OS, Java version,
  memory usage, disk space, network interfaces. Use when the user asks
  about system status, health, or diagnostics.
allowed-tools: shell
---

# System Info

Collect and report system information using shell commands.

## What to gather

1. **OS**: `uname -a`
2. **Java**: `java -version 2>&1`
3. **Memory**: `vm_stat` (macOS) or `free -h` (Linux)
4. **Disk**: `df -h`
5. **Uptime**: `uptime`

Present results in a clean summary format.
