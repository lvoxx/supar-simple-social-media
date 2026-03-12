# Ansible — Node Configuration

**Version:** Ansible 10.x (ansible-core 2.17)  
**Scope:** EKS worker node baseline hardening, system packages, kernel tuning, monitoring agent installation  
**Target:** EC2 instances in all EKS node groups (managed nodes)

> Ansible does **not** manage Kubernetes workloads — that is Helm + ArgoCD's job.  
> Ansible configures the **OS layer** of each EC2 instance.

---

## ansible.cfg

```ini
[defaults]
inventory          = inventories/
roles_path         = roles/
remote_user        = ec2-user
private_key_file   = ~/.ssh/sssm-eks.pem
host_key_checking  = False
forks              = 20
timeout            = 30
stdout_callback    = yaml
stderr_callback    = yaml
log_path           = /tmp/ansible.log

[ssh_connection]
ssh_args         = -o ControlMaster=auto -o ControlPersist=60s -o ServerAliveInterval=30
pipelining       = True
transfer_method  = smart

[privilege_escalation]
become        = True
become_method = sudo
become_user   = root
```

---

## Dynamic inventory (AWS EC2)

Instances are discovered automatically from AWS using the `aws_ec2` inventory plugin. No static host files.

```yaml
# inventories/prod/aws_ec2.yaml

plugin: amazon.aws.aws_ec2

regions:
  - us-east-1

filters:
  tag:Project: sssm
  tag:Env:     prod
  instance-state-name: running

keyed_groups:
  - key:  tags.NodeGroup
    prefix: node_group
  - key:  placement.availability_zone
    prefix: az

hostnames:
  - private-ip-address    # use private IP — Ansible runs from within VPC (bastion or CI)

compose:
  ansible_host: private_ip_address
```

### Generated groups from node group tags

| Ansible group | Matches |
|--------------|---------|
| `node_group_system` | system node group instances |
| `node_group_app` | app node group instances |
| `node_group_infra` | infra node group instances |
| `node_group_ai_gpu` | ai-gpu node group instances |

---

## Role overview

| Role | Applied to | What it does |
|------|-----------|-------------|
| `common` | All nodes | System packages, NTP, timezone, ulimits, sysctl tuning |
| `security-hardening` | All nodes | SSH hardening, auditd, fail2ban, AIDE, CIS Level 1 controls |
| `eks-node` | All nodes | EKS-specific kubelet config, containerd config, log rotation |
| `monitoring-agent` | All nodes | CloudWatch agent, Prometheus node exporter |
| `gpu-driver` | `ai-gpu` only | NVIDIA driver, CUDA toolkit, nvidia-smi validation |

---

## Role: common

```yaml
# roles/common/tasks/main.yaml

- name: Set timezone to UTC
  community.general.timezone:
    name: UTC

- name: Install baseline packages
  dnf:
    name:
      - amazon-ssm-agent
      - aws-cli
      - curl
      - jq
      - htop
      - iotop
      - nc
      - tcpdump
      - rsync
    state: present
    update_cache: true

- name: Enable and start SSM agent
  systemd:
    name:    amazon-ssm-agent
    enabled: true
    state:   started

- name: Configure NTP (chronyd)
  template:
    src:   chrony.conf.j2
    dest:  /etc/chrony.conf
    owner: root
    group: root
    mode:  "0644"
  notify: restart chronyd

- name: Set system-wide ulimits for container workloads
  pam_limits:
    domain: "*"
    limit_type: "{{ item.type }}"
    limit_item: "{{ item.item }}"
    value:      "{{ item.value }}"
  loop:
    - { type: soft, item: nofile,   value: "1048576" }
    - { type: hard, item: nofile,   value: "1048576" }
    - { type: soft, item: nproc,    value: "65536"   }
    - { type: hard, item: nproc,    value: "65536"   }
    - { type: soft, item: memlock,  value: unlimited  }
    - { type: hard, item: memlock,  value: unlimited  }

- name: Tune kernel parameters for Kubernetes and high-connection workloads
  sysctl:
    name:  "{{ item.key }}"
    value: "{{ item.value }}"
    state:  present
    reload: true
  loop:
    - { key: net.ipv4.ip_forward,                    value: "1"       }
    - { key: net.bridge.bridge-nf-call-iptables,     value: "1"       }
    - { key: net.bridge.bridge-nf-call-ip6tables,    value: "1"       }
    - { key: net.ipv4.tcp_fin_timeout,               value: "15"      }
    - { key: net.core.somaxconn,                     value: "32768"   }
    - { key: net.ipv4.tcp_max_syn_backlog,           value: "16384"   }
    - { key: vm.max_map_count,                       value: "262144"  }  # required for Elasticsearch
    - { key: vm.swappiness,                          value: "1"       }
    - { key: fs.inotify.max_user_watches,            value: "524288"  }
    - { key: fs.inotify.max_user_instances,          value: "8192"    }
    - { key: kernel.pid_max,                         value: "4194304" }
```

---

## Role: security-hardening

```yaml
# roles/security-hardening/tasks/main.yaml

- name: Harden SSH configuration
  template:
    src:  sshd_config.j2
    dest: /etc/ssh/sshd_config
    mode: "0600"
  notify: restart sshd

# sshd_config.j2:
#   PermitRootLogin no
#   PasswordAuthentication no
#   PubkeyAuthentication yes
#   AllowTcpForwarding no
#   X11Forwarding no
#   ClientAliveInterval 300
#   ClientAliveCountMax 3
#   MaxAuthTries 3
#   LoginGraceTime 30

- name: Install and configure fail2ban
  block:
    - dnf:
        name: fail2ban
        state: present
    - template:
        src:  jail.local.j2
        dest: /etc/fail2ban/jail.local
        mode: "0644"
    - systemd:
        name:    fail2ban
        enabled: true
        state:   started

- name: Enable and configure auditd
  block:
    - dnf:
        name: audit
        state: present
    - template:
        src:  audit.rules.j2
        dest: /etc/audit/rules.d/sssm.rules
        mode: "0640"
    - systemd:
        name:    auditd
        enabled: true
        state:   started

- name: Disable unused kernel modules
  kernel_module:
    name:  "{{ item }}"
    state: absent
  loop:
    - dccp
    - sctp
    - rds
    - tipc

- name: Configure firewalld — allow only required ports
  block:
    - dnf: { name: firewalld, state: present }
    - systemd: { name: firewalld, enabled: true, state: started }
    - firewalld:
        port:      "{{ item }}/tcp"
        permanent: true
        state:     enabled
        immediate: true
      loop:
        - "22"      # SSH (restricted by SG; belt-and-suspenders)
        - "10250"   # Kubelet API
        - "10255"   # Kubelet read-only
        - "30000-32767"  # NodePort range
```

---

## Role: eks-node

```yaml
# roles/eks-node/tasks/main.yaml

- name: Configure containerd — disable CRI v1 alpha, enable SystemdCgroup
  template:
    src:  containerd-config.toml.j2
    dest: /etc/containerd/config.toml
    mode: "0644"
  notify: restart containerd

- name: Configure kubelet extra args
  lineinfile:
    path:   /etc/eks/bootstrap.sh
    regexp: "^KUBELET_EXTRA_ARGS"
    line: >-
      KUBELET_EXTRA_ARGS="
        --max-pods={{ max_pods_per_node }}
        --kube-reserved=cpu=250m,memory=1Gi,ephemeral-storage=1Gi
        --system-reserved=cpu=250m,memory=512Mi,ephemeral-storage=1Gi
        --eviction-hard=memory.available<200Mi,nodefs.available<10%
        --image-gc-high-threshold=75
        --image-gc-low-threshold=65
      "

- name: Configure log rotation for container logs
  template:
    src:  logrotate-containers.j2
    dest: /etc/logrotate.d/containers
    mode: "0644"

# logrotate-containers.j2:
# /var/log/containers/*.log {
#   daily
#   missingok
#   rotate 5
#   compress
#   delaycompress
#   copytruncate
#   maxsize 100M
# }
```

---

## Role: monitoring-agent

```yaml
# roles/monitoring-agent/tasks/main.yaml

- name: Install CloudWatch agent
  block:
    - get_url:
        url:  https://s3.amazonaws.com/amazoncloudwatch-agent/amazon_linux/amd64/latest/amazon-cloudwatch-agent.rpm
        dest: /tmp/amazon-cloudwatch-agent.rpm
    - dnf: { name: /tmp/amazon-cloudwatch-agent.rpm, state: present }
    - template:
        src:  cloudwatch-agent-config.json.j2
        dest: /opt/aws/amazon-cloudwatch-agent/etc/amazon-cloudwatch-agent.json
    - systemd:
        name:    amazon-cloudwatch-agent
        enabled: true
        state:   started

- name: Install Prometheus node exporter
  block:
    - get_url:
        url:  "https://github.com/prometheus/node_exporter/releases/download/v1.8.1/node_exporter-1.8.1.linux-amd64.tar.gz"
        dest: /tmp/node_exporter.tar.gz
    - unarchive:
        src:        /tmp/node_exporter.tar.gz
        dest:       /usr/local/bin/
        remote_src: true
        extra_opts: [--strip-components=1]
    - template:
        src:  node_exporter.service.j2
        dest: /etc/systemd/system/node_exporter.service
    - systemd:
        name:          node_exporter
        enabled:       true
        state:         started
        daemon_reload: true
```

---

## Role: gpu-driver

```yaml
# roles/gpu-driver/tasks/main.yaml
# Applied only to ai-gpu node group

- name: Install NVIDIA driver prerequisites
  dnf:
    name:
      - kernel-devel
      - kernel-headers
      - gcc
      - make
      - dkms
    state: present

- name: Add NVIDIA CUDA repository
  get_url:
    url:  https://developer.download.nvidia.com/compute/cuda/repos/rhel9/x86_64/cuda-rhel9.repo
    dest: /etc/yum.repos.d/cuda-rhel9.repo

- name: Install NVIDIA driver and CUDA toolkit
  dnf:
    name:
      - "nvidia-driver-{{ nvidia_driver_version }}"
      - "cuda-toolkit-{{ cuda_version }}"
    state: present

- name: Verify GPU detected
  command: nvidia-smi
  register: nvidia_smi_output
  changed_when: false

- name: Print GPU info
  debug:
    msg: "{{ nvidia_smi_output.stdout_lines }}"

- name: Configure containerd for NVIDIA runtime
  template:
    src:  containerd-nvidia.toml.j2
    dest: /etc/containerd/config.toml
    mode: "0644"
  notify: restart containerd
```

---

## Playbooks

### node-hardening.yaml — main playbook

```yaml
# ansible/node-hardening.yaml

---
- name: Apply baseline configuration to all EKS nodes
  hosts: all
  gather_facts: true
  vars_files:
    - "inventories/{{ env }}/group_vars/all.yaml"

  roles:
    - role: common
      tags: [common, always]
    - role: security-hardening
      tags: [security]
    - role: eks-node
      tags: [eks]
    - role: monitoring-agent
      tags: [monitoring]

- name: Apply GPU drivers to ai-gpu nodes only
  hosts: node_group_ai_gpu
  gather_facts: true

  roles:
    - role: gpu-driver
      tags: [gpu]
```

### cluster-bootstrap.yaml — one-time post-Terraform

```yaml
# ansible/cluster-bootstrap.yaml

---
- name: Bootstrap EKS cluster — runs once after terraform apply
  hosts: localhost
  connection: local
  gather_facts: false

  vars_files:
    - "inventories/{{ env }}/group_vars/all.yaml"

  tasks:
    - name: Update kubeconfig
      command: >
        aws eks update-kubeconfig
          --region {{ aws_region }}
          --name {{ cluster_name }}
          --profile {{ aws_profile }}

    - name: Create application namespaces
      kubernetes.core.k8s:
        state: present
        definition:
          apiVersion: v1
          kind: Namespace
          metadata:
            name: "{{ item }}"
            labels:
              env: "{{ env }}"
      loop:
        - sssm
        - sssm-infra
        - monitoring
        - argocd
        - external-secrets

    - name: Create Secrets Manager ExternalSecret ClusterSecretStore
      kubernetes.core.k8s:
        state: present
        src:   files/external-secret-store.yaml

    - name: Install ArgoCD via Helm
      kubernetes.core.helm:
        name:          argocd
        chart_ref:     argo/argo-cd
        release_namespace: argocd
        values_files:
          - "{{ playbook_dir }}/../helm/charts/platform/argocd-values.yaml"
        wait: true

    - name: Apply ArgoCD App-of-Apps
      kubernetes.core.k8s:
        state: present
        src:   "{{ playbook_dir }}/../argocd/bootstrap/app-of-apps.yaml"
```

---

## Group variables

```yaml
# inventories/prod/group_vars/all.yaml

env:          prod
aws_region:   us-east-1
aws_profile:  sssm-prod
cluster_name: sssm-prod
project:      sssm

# Per-role variables
max_pods_per_node: 110

nvidia_driver_version: "550"
cuda_version:          "12-4"

# Versions
node_exporter_version: "1.8.1"
```
