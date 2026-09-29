export enum ConversationStep {
  IDLE = "idle",
  SELECT_CATEGORY = "select_category",     // FIRST step: "Материал" + expense categories (buttons)
  SELECT_PROJECT = "select_project",       // only in the "Материал" branch
  AWAIT_CATEGORY_DETAIL = "await_category_detail", // answering the category's follow-up question
  AWAIT_PHOTO = "await_photo",
  AWAIT_MORE_PAGES = "await_more_pages",   // waiting for more pages or "done"
  PROCESSING_OCR = "processing_ocr",       // OCR+Gemini in progress
  AWAIT_SUM = "await_sum",
  CONFIRM_SUM = "confirm_sum",             // sum mismatch confirmation
  AWAIT_DESCRIPTION = "await_desc",
  AWAIT_STORE = "await_store",
  SAVING = "saving",
}

export interface ConversationState {
  telegramId: number;
  step: ConversationStep;
  sessionId?: string;            // Session Log correlation id (UUID)
  projectName?: string;
  projectDriveUrl?: string;
  projectSheetsUrl?: string;
  // Multi-page support (replaces single photoFileId)
  photoFileId?: string;          // DEPRECATED: kept for backward compat
  photoFileIds?: string[];       // array of photo file_ids
  sum?: number;
  description?: string;
  storeName?: string;
  // OCR results
  ocrDescription?: string;       // description from Gemini
  ocrStoreName?: string;         // store name from Gemini
  ocrGrossAmount?: number;       // gross amount from Gemini (verification only)
  // Category selection (chosen FIRST, right after /start)
  isMaterial?: boolean;          // true if the built-in "Материал" branch (fills Projekt, empty Kategoria)
  categoryName?: string;         // chosen expense category label (empty for the Материал branch)
  categoryExtraPrompt?: string;  // follow-up question for the chosen category (if any, e.g. "Впиши номер машины")
  categoryDetail?: string;       // user's answer to the follow-up question; used as Opis (description) override
  updatedAt: string;
}

export function emptyState(telegramId: number): ConversationState {
  return {
    telegramId,
    step: ConversationStep.IDLE,
    updatedAt: new Date().toISOString(),
  };
}
