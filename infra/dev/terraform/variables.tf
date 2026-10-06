variable "project_id" {
  type    = string
  default = "starry-tracker-505110-s3"
}

variable "project_number" {
  type    = string
  default = "820040091656"
}

variable "region" {
  type    = string
  default = "europe-central2"
}

variable "zone" {
  type    = string
  default = "europe-central2-a"
}

# --- VM ---
variable "vm_name" {
  type    = string
  default = "foremen-dev"
}

variable "machine_type" {
  type    = string
  default = "e2-small"
}

variable "boot_disk_size" {
  type    = number
  default = 20
}

variable "network_tag" {
  type    = string
  default = "foremen-dev"
}

# --- Artifact Registry ---
variable "ar_repo" {
  type    = string
  default = "foremen"
}

# --- First admin bootstrap (non-secret) ---
variable "admin_email" {
  type    = string
  default = "cto+dev@loaders.dev"
}

variable "db_name" {
  type    = string
  default = "foremen"
}

variable "db_user" {
  type    = string
  default = "foremen"
}

# --- GitHub repos (owner/name) for Cloud Build triggers ---
variable "github_owner" {
  type    = string
  default = "ctoloaders"
}

variable "github_repo_backend" {
  type    = string
  default = "foremen"
}

variable "github_repo_frontend" {
  type    = string
  default = "foremen-frontend"
}

variable "deploy_branch" {
  type    = string
  default = "main"
}

# The Cloud Build 2nd-gen GitHub connection name. The connection itself is created
# via a one-time OAuth in the console (see cloudbuild.tf header); set this to the
# connection's name so Terraform can wire repositories/triggers to it.
variable "cb_connection_name" {
  type    = string
  default = "github-foremen"
}

locals {
  ar_host        = "${var.region}-docker.pkg.dev"
  ar_image_base  = "${var.region}-docker.pkg.dev/${var.project_id}/${var.ar_repo}"
  image_backend  = "${local.ar_image_base}/backend"
  image_frontend = "${local.ar_image_base}/frontend"
  vm_sa_email    = "foremen-dev-vm@${var.project_id}.iam.gserviceaccount.com"
  ci_sa_email    = "foremen-ci@${var.project_id}.iam.gserviceaccount.com"
  cloudbuild_sa  = "${var.project_number}@cloudbuild.gserviceaccount.com"
}
