# =============================================================================
# Cloud Build CI/CD (GCP-native, replaces GitHub Actions)
#
# ONE-TIME MANUAL STEP (OAuth handshake cannot be done via Terraform/CLI):
#   Install & authorize the Cloud Build GitHub App and create a 2nd-gen host
#   connection named `${var.cb_connection_name}` in region ${var.region}:
#     Console -> Cloud Build -> Repositories -> 2nd gen -> Create host connection
#     https://console.cloud.google.com/cloud-build/repositories/2nd-gen?project=${var.project_id}
#   Authorize the `ctoloaders` org and grant access to both repos.
#
# After the connection exists, Terraform manages the repository links and the
# triggers below. If you prefer Terraform to CREATE the connection too, it still
# requires an OAuth token stored in Secret Manager (GitHub PAT) — the console
# flow is simpler and is the documented path here.
# =============================================================================

# The manually-created 2nd-gen GitHub connection, referenced by its resource id.
locals {
  cb_connection_id = "projects/${var.project_id}/locations/${var.region}/connections/${var.cb_connection_name}"
}

# Link the two repositories under that connection.
resource "google_cloudbuildv2_repository" "backend" {
  name              = var.github_repo_backend
  location          = var.region
  parent_connection = local.cb_connection_id
  remote_uri        = "https://github.com/${var.github_owner}/${var.github_repo_backend}.git"
}

resource "google_cloudbuildv2_repository" "frontend" {
  name              = var.github_repo_frontend
  location          = var.region
  parent_connection = local.cb_connection_id
  remote_uri        = "https://github.com/${var.github_owner}/${var.github_repo_frontend}.git"
}

# --- Build service account ---
# Cloud Build 2nd-gen builds REQUIRE a user-managed service account (the legacy
# Google-managed 820040091656@cloudbuild SA is rejected with
# "provide a user-managed service account"). We reuse the user-managed CI SA,
# which already has deploy roles (iam.tf). With a user-specified build SA and
# CLOUD_LOGGING_ONLY, it also needs logging.logWriter to write build logs.
resource "google_project_iam_member" "ci_logwriter" {
  project = var.project_id
  role    = "roles/logging.logWriter"
  member  = "serviceAccount:${google_service_account.ci.email}"
}

# --- Backend trigger: push to main builds + deploys only the backend ---
resource "google_cloudbuild_trigger" "backend" {
  name     = "foremen-backend-deploy"
  location = var.region

  repository_event_config {
    repository = google_cloudbuildv2_repository.backend.id
    push {
      branch = "^${var.deploy_branch}$"
    }
  }

  filename = "cloudbuild.backend.yaml"

  # 2nd-gen triggers require an explicit build service account.
  service_account = google_service_account.ci.id

  substitutions = {
    _AR_IMAGE = local.image_backend
    _VM_NAME  = var.vm_name
    _ZONE     = var.zone
    _SERVICE  = "backend"
  }

  depends_on = [google_project_iam_member.ci_roles, google_project_iam_member.ci_logwriter]
}

# --- Frontend trigger: push to main builds + deploys only the frontend ---
resource "google_cloudbuild_trigger" "frontend" {
  name     = "foremen-frontend-deploy"
  location = var.region

  repository_event_config {
    repository = google_cloudbuildv2_repository.frontend.id
    push {
      branch = "^${var.deploy_branch}$"
    }
  }

  filename = "cloudbuild.frontend.yaml"

  # 2nd-gen triggers require an explicit build service account.
  service_account = google_service_account.ci.id

  substitutions = {
    _AR_IMAGE = local.image_frontend
    _VM_NAME  = var.vm_name
    _ZONE     = var.zone
    _SERVICE  = "frontend"
  }

  depends_on = [google_project_iam_member.ci_roles, google_project_iam_member.ci_logwriter]
}
