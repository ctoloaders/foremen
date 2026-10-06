# --- Service accounts ---
resource "google_service_account" "vm" {
  account_id   = "foremen-dev-vm"
  display_name = "Foremen dev VM"
}

resource "google_service_account" "ci" {
  account_id   = "foremen-ci"
  display_name = "Foremen GitHub Actions CI"
}

# --- VM SA: least privilege to read secrets, pull images, write logs ---
resource "google_project_iam_member" "vm_roles" {
  for_each = toset([
    "roles/secretmanager.secretAccessor",
    "roles/artifactregistry.reader",
    "roles/logging.logWriter",
  ])
  project = var.project_id
  role    = each.value
  member  = "serviceAccount:${google_service_account.vm.email}"
}

# --- CI SA (also usable by WIF): push images, SSH via IAP/OS Login, manage instance ---
resource "google_project_iam_member" "ci_roles" {
  for_each = toset([
    "roles/artifactregistry.writer",
    "roles/compute.osAdminLogin",
    "roles/compute.instanceAdmin.v1",
    "roles/iap.tunnelResourceAccessor",
    "roles/logging.viewer",
  ])
  project = var.project_id
  role    = each.value
  member  = "serviceAccount:${google_service_account.ci.email}"
}

# CI SA must be able to actAs the VM SA (for OS Login SSH).
resource "google_service_account_iam_member" "ci_actas_vm" {
  service_account_id = google_service_account.vm.name
  role               = "roles/iam.serviceAccountUser"
  member             = "serviceAccount:${google_service_account.ci.email}"
}
