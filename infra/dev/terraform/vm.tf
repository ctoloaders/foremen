# --- Reserved static external IP (stable *.bc.googleusercontent.com hostname) ---
resource "google_compute_address" "vm_ip" {
  name   = "foremen-dev-ip"
  region = var.region
}

# --- Firewall: public web (80) ---
resource "google_compute_firewall" "web" {
  name          = "allow-${var.network_tag}"
  network       = "default"
  description   = "Foremen dev inbound"
  direction     = "INGRESS"
  source_ranges = ["0.0.0.0/0"]
  target_tags   = [var.network_tag]
  allow {
    protocol = "tcp"
    ports    = ["80"]
  }
}

# --- Firewall: SSH (22) only from Google IAP range ---
resource "google_compute_firewall" "iap_ssh" {
  name          = "allow-${var.network_tag}-iap-ssh"
  network       = "default"
  description   = "Foremen dev SSH via IAP only"
  direction     = "INGRESS"
  source_ranges = ["35.235.240.0/20"]
  target_tags   = [var.network_tag]
  allow {
    protocol = "tcp"
    ports    = ["22"]
  }
}

# --- The VM ---
resource "google_compute_instance" "vm" {
  name         = var.vm_name
  machine_type = var.machine_type
  zone         = var.zone
  tags         = [var.network_tag]

  boot_disk {
    initialize_params {
      image = "debian-cloud/debian-12"
      size  = var.boot_disk_size
      type  = "pd-balanced"
    }
  }

  network_interface {
    network    = "default"
    subnetwork = "default"
    access_config {
      nat_ip = google_compute_address.vm_ip.address
    }
  }

  service_account {
    email  = google_service_account.vm.email
    scopes = ["https://www.googleapis.com/auth/cloud-platform"]
  }

  # Metadata mirrors what 50-vm.sh set. Secret VALUES are not here — only the
  # NAMES of Secret Manager secrets the startup script reads at boot via the SA.
  metadata = {
    enable-oslogin           = "TRUE"
    project-id               = var.project_id
    region                   = var.region
    ar-host                  = local.ar_host
    image-backend            = "${local.image_backend}:dev"
    image-frontend           = "${local.image_frontend}:dev"
    db-name                  = var.db_name
    db-user                  = var.db_user
    admin-email              = var.admin_email
    secret-db-password       = "foremen-dev-db-password"
    secret-jwt               = "foremen-dev-jwt-secret"
    secret-admin-password    = "foremen-dev-admin-password"
    secret-google-places-key = "foremen-dev-google-places-key"
    # Startup script as a metadata entry (key "startup-script") — matches how the
    # VM was created, so Terraform doesn't try to null it out.
    startup-script = file("${path.module}/../vm-startup.sh")
  }

  # Boot disk image/type were pd-standard originally; ignore image churn so
  # Terraform never tries to rebuild the running VM.
  lifecycle {
    ignore_changes = [
      boot_disk[0].initialize_params[0].image,
      boot_disk[0].initialize_params[0].type,
    ]
  }
}

output "vm_external_ip" {
  value = google_compute_address.vm_ip.address
}

output "public_url" {
  value = "http://${google_compute_address.vm_ip.address}/"
}
