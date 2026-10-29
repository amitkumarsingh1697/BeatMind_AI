variable "cloudflare_api_token" {
  type      = string
  sensitive = true
}

variable "cloudflare_account_id" {
  type = string
}

variable "cloudflare_zone_id" {
  type = string
}

variable "oci_tenancy_ocid" {
  type = string
}

variable "oci_user_ocid" {
  type = string
}

variable "oci_fingerprint" {
  type = string
}

variable "oci_private_key" {
  type      = string
  sensitive = true
}

variable "oci_region" {
  type    = string
  default = "ap-mumbai-1"
}

variable "oci_compartment_ocid" {
  type = string
}

variable "oci_image_ocid" {
  type = string
}

variable "ssh_public_key" {
  type = string
}

variable "admin_cidrs" {
  type    = list(string)
  default = []
}
