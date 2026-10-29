# Cloudflare edge for one environment (ARCH-08, ARCH-09): R2 bucket for audio and DNS records
# proxied through Cloudflare (CDN, WAF, DDoS protection).

terraform {
  required_providers {
    cloudflare = {
      source  = "cloudflare/cloudflare"
      version = "~> 5.12"
    }
  }
}

variable "account_id" {
  type        = string
  description = "Cloudflare account ID."
}

variable "zone_id" {
  type        = string
  description = "Cloudflare zone ID of the product domain."
}

variable "environment" {
  type        = string
  description = "staging or production."
}

variable "hostnames" {
  type        = map(string)
  description = "DNS record name to origin IPv4 address, for example { \"app\" = \"203.0.113.10\" }."
}

variable "r2_location" {
  type        = string
  default     = "apac"
  description = "R2 location hint. APAC keeps audio close to Indian users (DEC-101)."
}

resource "cloudflare_r2_bucket" "audio" {
  account_id = var.account_id
  name       = "beatmind-audio-${var.environment}"
  location   = var.r2_location
}

resource "cloudflare_dns_record" "origin" {
  for_each = var.hostnames

  zone_id = var.zone_id
  name    = each.key
  type    = "A"
  content = each.value
  proxied = true
  ttl     = 1
}

data "cloudflare_ip_ranges" "this" {}

output "audio_bucket" {
  value = cloudflare_r2_bucket.audio.name
}

output "cloudflare_ipv4_cidrs" {
  description = "Cloudflare's edge ranges; the origin accepts web traffic only from these."
  value       = data.cloudflare_ip_ranges.this.ipv4_cidrs
}
