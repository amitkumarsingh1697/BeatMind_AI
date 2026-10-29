# One always-free ARM VM per environment running the web, API and CPU worker containers
# (ADR-006). GPU workers run elsewhere (startup credits or a GPU marketplace).
# Free-tier limits must be re-checked before each apply (check C-03, assumption A-030).

terraform {
  required_providers {
    oci = {
      source  = "oracle/oci"
      version = "~> 7.20"
    }
  }
}

variable "compartment_id" {
  type = string
}

variable "environment" {
  type = string
}

variable "image_id" {
  type        = string
  description = "OCID of an Ubuntu ARM (aarch64) image in the region."
}

variable "ocpus" {
  type    = number
  default = 2
}

variable "memory_gbs" {
  type    = number
  default = 12
}

variable "ssh_public_key" {
  type        = string
  description = "Public key for the deploy user. Never put a private key in this repository."
}

variable "admin_cidrs" {
  type        = list(string)
  description = "Addresses allowed to reach SSH. Keep this list short."
}

variable "web_ingress_cidrs" {
  type        = list(string)
  description = "Addresses allowed to reach HTTPS: Cloudflare's edge ranges only."
}

data "oci_identity_availability_domains" "ads" {
  compartment_id = var.compartment_id
}

resource "oci_core_vcn" "main" {
  compartment_id = var.compartment_id
  cidr_blocks    = ["10.20.0.0/16"]
  display_name   = "beatmind-${var.environment}"
}

resource "oci_core_internet_gateway" "main" {
  compartment_id = var.compartment_id
  vcn_id         = oci_core_vcn.main.id
  display_name   = "beatmind-${var.environment}"
}

resource "oci_core_route_table" "public" {
  compartment_id = var.compartment_id
  vcn_id         = oci_core_vcn.main.id
  display_name   = "beatmind-${var.environment}-public"

  route_rules {
    destination       = "0.0.0.0/0"
    network_entity_id = oci_core_internet_gateway.main.id
  }
}

resource "oci_core_security_list" "public" {
  compartment_id = var.compartment_id
  vcn_id         = oci_core_vcn.main.id
  display_name   = "beatmind-${var.environment}-public"

  egress_security_rules {
    protocol    = "all"
    destination = "0.0.0.0/0"
  }

  dynamic "ingress_security_rules" {
    for_each = var.web_ingress_cidrs
    content {
      protocol = "6"
      source   = ingress_security_rules.value
      tcp_options {
        min = 443
        max = 443
      }
    }
  }

  dynamic "ingress_security_rules" {
    for_each = var.admin_cidrs
    content {
      protocol = "6"
      source   = ingress_security_rules.value
      tcp_options {
        min = 22
        max = 22
      }
    }
  }
}

resource "oci_core_subnet" "public" {
  compartment_id    = var.compartment_id
  vcn_id            = oci_core_vcn.main.id
  cidr_block        = "10.20.1.0/24"
  display_name      = "beatmind-${var.environment}-public"
  route_table_id    = oci_core_route_table.public.id
  security_list_ids = [oci_core_security_list.public.id]
}

resource "oci_core_instance" "app" {
  compartment_id      = var.compartment_id
  availability_domain = data.oci_identity_availability_domains.ads.availability_domains[0].name
  display_name        = "beatmind-${var.environment}-app"
  shape               = "VM.Standard.A1.Flex"

  shape_config {
    ocpus         = var.ocpus
    memory_in_gbs = var.memory_gbs
  }

  source_details {
    source_type = "image"
    source_id   = var.image_id
  }

  create_vnic_details {
    subnet_id        = oci_core_subnet.public.id
    assign_public_ip = true
  }

  metadata = {
    ssh_authorized_keys = var.ssh_public_key
    user_data           = base64encode(file("${path.module}/cloud-init.yaml"))
  }
}

output "public_ip" {
  value = oci_core_instance.app.public_ip
}
