# Enable the APIs the dev environment needs. Idempotent; safe to keep enabled.
resource "google_project_service" "apis" {
  for_each = toset([
    "compute.googleapis.com",
    "artifactregistry.googleapis.com",
    "secretmanager.googleapis.com",
    "cloudbuild.googleapis.com",
    "iam.googleapis.com",
    "iamcredentials.googleapis.com",
    "logging.googleapis.com",
    "iap.googleapis.com",
  ])
  service            = each.value
  disable_on_destroy = false
}
