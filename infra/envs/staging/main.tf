# Staging environment (US-003). Run from CI only: .github/workflows/infra.yml.
# Secrets arrive as TF_VAR_* environment variables from GitHub environment secrets.

terraform {
  required_version = ">= 1.10"

  # State lives in a private R2 bucket created by hand once (see infra/README.md).
  # Bucket, endpoint and keys are passed at init time with -backend-config.
  backend "s3" {
    key                         = "beatmind/staging.tfstate"
    region                      = "auto"
    skip_credentials_validation = true
    skip_region_validation      = true
    skip_requesting_account_id  = true
    skip_metadata_api_check     = true
    skip_s3_checksum            = true
    use_path_style              = true
  }

  required_providers {
    cloudflare = {
      source  = "cloudflare/cloudflare"
      version = "~> 5.12"
    }
    oci = {
      source  = "oracle/oci"
      version = "~> 7.20"
    }
  }
}

provider "cloudflare" {
  api_token = var.cloudflare_api_token
}

provider "oci" {
  tenancy_ocid = var.oci_tenancy_ocid
  user_ocid    = var.oci_user_ocid
  fingerprint  = var.oci_fingerprint
  private_key  = var.oci_private_key
  region       = var.oci_region
}

module "app_host" {
  source = "../../modules/app-host"

  environment       = "staging"
  compartment_id    = var.oci_compartment_ocid
  image_id          = var.oci_image_ocid
  ocpus             = 1
  memory_gbs        = 6
  ssh_public_key    = var.ssh_public_key
  admin_cidrs       = var.admin_cidrs
  web_ingress_cidrs = module.edge.cloudflare_ipv4_cidrs
}

module "edge" {
  source = "../../modules/edge"

  environment = "staging"
  account_id  = var.cloudflare_account_id
  zone_id     = var.cloudflare_zone_id
  hostnames   = { "staging" = module.app_host.public_ip, "api-staging" = module.app_host.public_ip }
}

output "app_public_ip" {
  value = module.app_host.public_ip
}

output "audio_bucket" {
  value = module.edge.audio_bucket
}
