# --- Artifact Registry (Docker) ---
resource "google_artifact_registry_repository" "foremen" {
  location      = var.region
  repository_id = var.ar_repo
  format        = "DOCKER"
  description   = "Foremen dev images"
}

# --- Secret Manager: manage the secret CONTAINERS only ---
# Terraform never creates/reads versions, so no secret value is ever in state or
# the plan. Versions are added out-of-band by infra/dev/21-load-secrets.sh.
resource "google_secret_manager_secret" "secrets" {
  for_each = toset([
    "foremen-dev-db-password",
    "foremen-dev-jwt-secret",
    "foremen-dev-admin-password",
    "foremen-dev-google-places-key",
  ])
  secret_id = each.value
  replication {
    auto {}
  }
}
