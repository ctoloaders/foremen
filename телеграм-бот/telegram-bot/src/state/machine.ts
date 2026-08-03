export enum ConversationStep {
  IDLE = "idle",
  SELECT_PROJECT = "select_project",
  AWAIT_PHOTO = "await_photo",
  AWAIT_SUM = "await_sum",
  AWAIT_DESCRIPTION = "await_desc",
  AWAIT_STORE = "await_store",
  SAVING = "saving",
}

export interface ConversationState {
  telegramId: number;
  step: ConversationStep;
  projectName?: string;
  projectDriveUrl?: string;
  projectSheetsUrl?: string;
  photoFileId?: string;
  sum?: number;
  description?: string;
  storeName?: string;
  updatedAt: string;
}

export function emptyState(telegramId: number): ConversationState {
  return {
    telegramId,
    step: ConversationStep.IDLE,
    updatedAt: new Date().toISOString(),
  };
}
