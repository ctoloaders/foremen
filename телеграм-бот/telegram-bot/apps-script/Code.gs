/**
 * Google Apps Script — Webhook receiver for Bitrix24 automation.
 * Deployed as Web App (Anyone can access).
 *
 * Handles 3 actions:
 *   - upsert_project: add/update project + project_access entries
 *   - upsert_worker: add/update worker in workers registry
 *   - remove_worker: delete worker from registry
 *
 * IMPORTANT: After pasting this code, update CONFIG below with your actual values.
 */

// ===== CONFIGURATION =====
var CONFIG = {
  WEBHOOK_SECRET: "foremen-apps-script-secret-2024",
  
  // Workers Registry spreadsheet (contains: workers, project_access, bot_state)
  WORKERS_SPREADSHEET_ID: "10Y_f33aXzBhCz55y-l-GeX5raYeyTZb9gGiQt6azchs",
  WORKERS_SHEET_NAME: "workers",
  ACCESS_SHEET_NAME: "project_access",
  
  // Projects Registry spreadsheet (contains: projects)
  PROJECTS_SPREADSHEET_ID: "1YjSN9w_OpCFKNElj0Nrhvva2JgWlMtmSHbiVDFuSKaQ",
  PROJECTS_SHEET_NAME: "projects",
};

// ===== MAIN HANDLER =====

function doPost(e) {
  try {
    var data = JSON.parse(e.postData.contents);
    
    // Validate secret
    if (data.secret !== CONFIG.WEBHOOK_SECRET) {
      return jsonResponse(401, "error", "invalid secret");
    }
    
    // Route by action
    var action = data.action;
    switch (action) {
      case "upsert_project":
        return handleUpsertProject(data);
      case "upsert_worker":
        return handleUpsertWorker(data);
      case "remove_worker":
        return handleRemoveWorker(data);
      default:
        return jsonResponse(400, "error", "unknown action: " + action);
    }
  } catch (err) {
    return jsonResponse(500, "error", "internal error: " + err.message);
  }
}

// ===== ACTION HANDLERS =====

function handleUpsertProject(data) {
  // Validate required fields
  var missing = [];
  if (!data.project_name) missing.push("project_name");
  if (!data.google_drive_url) missing.push("google_drive_url");
  if (!data.google_sheets_url) missing.push("google_sheets_url");
  if (missing.length > 0) {
    return jsonResponse(400, "error", "missing fields: " + missing.join(", "));
  }
  
  // Validate URLs
  if (!data.google_drive_url.startsWith("https://")) {
    return jsonResponse(400, "error", "invalid google_drive_url");
  }
  if (!data.google_sheets_url.startsWith("https://")) {
    return jsonResponse(400, "error", "invalid google_sheets_url");
  }
  
  // Upsert project in projects sheet
  var projectsSheet = SpreadsheetApp.openById(CONFIG.PROJECTS_SPREADSHEET_ID)
    .getSheetByName(CONFIG.PROJECTS_SHEET_NAME);
  
  var projectsData = projectsSheet.getDataRange().getValues();
  var projectRowIndex = -1;
  for (var i = 1; i < projectsData.length; i++) {
    if (projectsData[i][0] === data.project_name) {
      projectRowIndex = i + 1; // 1-indexed
      break;
    }
  }
  
  var today = new Date().toISOString().split("T")[0];
  var row = [data.project_name, data.google_drive_url, data.google_sheets_url, "active", today];
  
  if (projectRowIndex > 0) {
    projectsSheet.getRange(projectRowIndex, 1, 1, 5).setValues([row]);
  } else {
    projectsSheet.appendRow(row);
  }
  
  // Upsert workers in project_access
  var workers = data.workers || [];
  if (workers.length > 0) {
    var accessSheet = SpreadsheetApp.openById(CONFIG.WORKERS_SPREADSHEET_ID)
      .getSheetByName(CONFIG.ACCESS_SHEET_NAME);
    var accessData = accessSheet.getDataRange().getValues();
    
    for (var w = 0; w < workers.length; w++) {
      var worker = workers[w];
      if (!worker.worker_name || worker.worker_name.trim() === "") continue;
      
      var accessRowIndex = -1;
      for (var j = 1; j < accessData.length; j++) {
        if (accessData[j][0] === data.project_name && accessData[j][1] === worker.worker_name) {
          accessRowIndex = j + 1;
          break;
        }
      }
      
      var accessRow = [data.project_name, worker.worker_name, worker.role_in_project || "other"];
      if (accessRowIndex > 0) {
        accessSheet.getRange(accessRowIndex, 1, 1, 3).setValues([accessRow]);
      } else {
        accessSheet.appendRow(accessRow);
        // Update local cache for subsequent iterations
        accessData.push(accessRow);
      }
    }
  }
  
  return jsonResponse(200, "ok", "project upserted");
}

function handleUpsertWorker(data) {
  // Validate required fields
  var missing = [];
  if (!data.bitrix_user_id) missing.push("bitrix_user_id");
  if (!data.worker_name) missing.push("worker_name");
  if (!data.role) missing.push("role");
  if (!data.telegram_id) missing.push("telegram_id");
  if (missing.length > 0) {
    return jsonResponse(400, "error", "missing fields: " + missing.join(", "));
  }
  
  // Validate role
  var validRoles = ["foreman", "pm", "estimator", "sales", "admin", "other"];
  if (validRoles.indexOf(data.role) === -1) {
    return jsonResponse(400, "error", "invalid role: " + data.role + ". Must be one of: " + validRoles.join(", "));
  }
  
  // Validate telegram_id is positive integer
  var tgId = parseInt(data.telegram_id);
  if (!tgId || tgId <= 0) {
    return jsonResponse(200, "ok", "telegram_id empty or invalid, skipped");
  }
  
  // Upsert worker
  var sheet = SpreadsheetApp.openById(CONFIG.WORKERS_SPREADSHEET_ID)
    .getSheetByName(CONFIG.WORKERS_SHEET_NAME);
  var rows = sheet.getDataRange().getValues();
  
  var workerRowIndex = -1;
  var currentRole = "";
  for (var i = 1; i < rows.length; i++) {
    if (String(rows[i][0]) === String(data.bitrix_user_id)) {
      workerRowIndex = i + 1;
      currentRole = rows[i][3];
      break;
    }
  }
  
  // Admin role is sticky — don't override if current role is admin
  var newRole = data.role;
  if (currentRole === "admin" && newRole !== "admin") {
    newRole = "admin"; // keep admin
  }
  
  var row = [data.bitrix_user_id, tgId, data.worker_name, newRole];
  
  if (workerRowIndex > 0) {
    sheet.getRange(workerRowIndex, 1, 1, 4).setValues([row]);
  } else {
    sheet.appendRow(row);
  }
  
  return jsonResponse(200, "ok", "worker upserted");
}

function handleRemoveWorker(data) {
  if (!data.bitrix_user_id) {
    return jsonResponse(400, "error", "missing field: bitrix_user_id");
  }
  
  var sheet = SpreadsheetApp.openById(CONFIG.WORKERS_SPREADSHEET_ID)
    .getSheetByName(CONFIG.WORKERS_SHEET_NAME);
  var rows = sheet.getDataRange().getValues();
  
  for (var i = 1; i < rows.length; i++) {
    if (String(rows[i][0]) === String(data.bitrix_user_id)) {
      sheet.deleteRow(i + 1);
      return jsonResponse(200, "ok", "worker removed");
    }
  }
  
  return jsonResponse(200, "ok", "worker not found, skipped");
}

// ===== HELPERS =====

function jsonResponse(code, status, message) {
  var body = JSON.stringify({ status: status, message: message });
  return ContentService.createTextOutput(body).setMimeType(ContentService.MimeType.JSON);
}

// ===== TEST FUNCTION (run manually to verify) =====
function testDoPost() {
  var e = {
    postData: {
      contents: JSON.stringify({
        secret: CONFIG.WEBHOOK_SECRET,
        action: "upsert_worker",
        bitrix_user_id: "test_user_1",
        worker_name: "Тест Тестов",
        role: "foreman",
        telegram_id: 123456789,
      })
    }
  };
  var result = doPost(e);
  Logger.log(result.getContent());
}
