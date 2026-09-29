import { Bot, Context, InlineKeyboard } from "grammy";
import { getState, setState, clearState } from "../state/store.js";
import { ConversationStep, ConversationState } from "../state/machine.js";
import { downloadFile } from "../services/telegram.js";
import { uploadReceiptArtifact } from "../services/receipt-upload.js";
import {
  getWorker,
  getProjectsForWorker,
  getExpenseCategories,
  appendReceiptRowCentral,
  type ExpenseCategory,
  type Project,
} from "../services/sheets.js";
import { sessionLog } from "../services/session-log.js";
import { withRetry } from "../utils/retry.js";
import { logger } from "../utils/logger.js";

const cancelKeyboard = new InlineKeyboard().text("❌ Отмена", "cancel");

/** Built-in first category: leads to project selection instead of an expense category. */
export const MATERIAL_LABEL = "Материал";

// In-memory caches for resolving callback indices. In webhook mode these may be cold after a
// restart, so both handlers reload from the sheet on a cache miss.
const userCategoriesCache = new Map<number, ExpenseCategory[]>();
const userProjectsCache = new Map<number, Project[]>();

/**
 * Builds the category keyboard shown right after /start: the built-in "Материал" (cat:0) first,
 * followed by each expense category from the sheet (cat:1, cat:2, ...). Caches the ordered
 * expense-category list for later index resolution.
 */
export async function buildCategoryKeyboard(telegramId: number): Promise<InlineKeyboard> {
  const categories = await getExpenseCategories();
  userCategoriesCache.set(telegramId, categories);

  const keyboard = new InlineKeyboard();
  keyboard.text(MATERIAL_LABEL, "cat:0").row();
  for (let i = 0; i < categories.length; i++) {
    keyboard.text(categories[i].category, `cat:${i + 1}`).row();
  }
  return keyboard;
}

/**
 * Handles a `cat:N` selection at the start of the flow.
 * - cat:0 → "Материал": show the worker's projects (project selection), Kategoria stays empty.
 * - cat:N (N≥1) → expense category: skip project; if it has a follow-up question ask it
 *   (the answer becomes the Opis/description), otherwise go straight to photo upload.
 *   Projekt stays empty; Kategoria = category label.
 */
export async function handleCategorySelection(
  ctx: Context,
  bot: Bot,
  state: ConversationState,
  data: string,
): Promise<void> {
  const telegramId = state.telegramId;
  const index = parseInt(data.slice("cat:".length), 10);
  if (isNaN(index)) return;

  // cat:0 = Материал branch.
  if (index === 0) {
    state.isMaterial = true;
    state.categoryName = undefined;
    await showProjects(ctx, state);
    return;
  }

  // cat:N (N≥1) = expense category (index-1 in the categories list).
  let categories = userCategoriesCache.get(telegramId);
  if (!categories) {
    try {
      categories = await getExpenseCategories();
      userCategoriesCache.set(telegramId, categories);
    } catch (err: any) {
      logger.error("Failed to reload categories on selection", { telegramId, error: err?.message });
      await ctx.reply("❌ Ошибка. Начните заново (/start).");
      return;
    }
  }

  const chosen = categories[index - 1];
  if (!chosen) {
    await ctx.reply("Категория не найдена. Начните заново (/start).");
    return;
  }

  state.isMaterial = false;
  state.categoryName = chosen.category;
  state.categoryExtraPrompt = chosen.extraPrompt;
  userCategoriesCache.delete(telegramId);

  if (chosen.extraPrompt) {
    // Ask the follow-up question; the answer becomes the description (Opis).
    state.step = ConversationStep.AWAIT_CATEGORY_DETAIL;
    await setState(state);
    if (state.sessionId) {
      await sessionLog.updateSession(state.sessionId, { step: ConversationStep.AWAIT_CATEGORY_DETAIL });
    }
    await ctx.reply(chosen.extraPrompt, { reply_markup: cancelKeyboard });
    return;
  }

  // No follow-up — go straight to photo upload.
  await goToPhoto(ctx, state);
}

/**
 * Handles the free-text answer to a category follow-up question (e.g. vehicle number for fuel).
 * Stores it as the description (Opis) and proceeds to photo upload.
 */
export async function handleCategoryDetail(
  ctx: Context,
  _bot: Bot,
  state: ConversationState,
  text: string,
): Promise<void> {
  const detail = text.trim();
  if (detail === "") {
    await ctx.reply("Введите ответ на вопрос.", { reply_markup: cancelKeyboard });
    return;
  }
  state.categoryDetail = detail;
  await goToPhoto(ctx, state);
}

/**
 * Shows the worker's available projects as buttons (Материал branch). Transitions to
 * SELECT_PROJECT. Reuses the p{index} callback contract.
 */
async function showProjects(ctx: Context, state: ConversationState): Promise<void> {
  const telegramId = state.telegramId;

  const worker = await getWorker(telegramId);
  if (!worker) {
    await ctx.reply("Доступ запрещён. Начните заново (/start).");
    return;
  }

  const projects = await getProjectsForWorker(worker);
  if (projects.length === 0) {
    await ctx.reply("У вас нет активных проектов. Обратитесь к менеджеру.");
    return;
  }

  userProjectsCache.set(telegramId, projects);

  const keyboard = new InlineKeyboard();
  for (let i = 0; i < projects.length; i++) {
    keyboard.text(projects[i].name, `p${i}`).row();
  }

  state.step = ConversationStep.SELECT_PROJECT;
  await setState(state);
  if (state.sessionId) {
    await sessionLog.updateSession(state.sessionId, { step: ConversationStep.SELECT_PROJECT });
  }

  await ctx.reply("Выберите объект (проект):", { reply_markup: keyboard });
}

/**
 * Handles a `p{index}` project selection (only reachable in the Материал branch).
 * Records the project and moves on to photo upload.
 */
export async function handleProjectSelection(ctx: Context): Promise<void> {
  const telegramId = ctx.from?.id;
  if (!telegramId) return;

  const data = ctx.callbackQuery?.data;
  if (!data?.startsWith("p")) return;
  const index = parseInt(data.slice(1), 10);
  if (isNaN(index)) return;

  let projects = userProjectsCache.get(telegramId);
  if (!projects) {
    const worker = await getWorker(telegramId);
    if (!worker) return;
    projects = await getProjectsForWorker(worker);
  }

  const project = projects[index];
  if (!project) {
    await ctx.answerCallbackQuery({ text: "Проект не найден" });
    return;
  }
  userProjectsCache.delete(telegramId);

  const state = await getState(telegramId);
  if (!state) {
    await ctx.answerCallbackQuery();
    await ctx.reply("Сессия истекла. Начните заново (/start).");
    return;
  }

  state.projectName = project.name;
  state.projectDriveUrl = project.googleDriveUrl;
  state.projectSheetsUrl = project.googleSheetsUrl;

  await ctx.answerCallbackQuery();
  if (state.sessionId) {
    await sessionLog.updateSession(state.sessionId, {
      projectName: project.name,
      projectDriveUrl: project.googleDriveUrl,
      projectSheetsUrl: project.googleSheetsUrl,
    });
  }
  logger.info("Project selected", { telegramId, project: project.name });

  await goToPhoto(ctx, state);
}

/** Transitions to AWAIT_PHOTO and prompts for the receipt/invoice image. */
async function goToPhoto(ctx: Context, state: ConversationState): Promise<void> {
  state.step = ConversationStep.AWAIT_PHOTO;
  await setState(state);
  if (state.sessionId) {
    await sessionLog.updateSession(state.sessionId, { step: ConversationStep.AWAIT_PHOTO });
  }
  await ctx.reply(
    "Пришлите фото чека/фактуры 📸\n💡 Для лучшего качества отправьте как файл (без сжатия).",
    { reply_markup: cancelKeyboard },
  );
}

/**
 * Pure helper: computes the receipt-row fields that depend on the category branch.
 *
 * - Материал branch: Projekt = project name, Kategoria = "" (empty), Opis = OCR/manual description.
 * - Other categories: Projekt = "" (empty), Kategoria = category label, Opis = follow-up answer
 *   when present, otherwise the OCR/manual description.
 */
export function receiptRowFields(state: {
  isMaterial?: boolean;
  projectName?: string;
  categoryName?: string;
  categoryDetail?: string;
  description?: string;
}): { project: string; category: string; description: string } {
  if (state.isMaterial) {
    return {
      project: state.projectName ?? "",
      category: "",
      description: state.description ?? "",
    };
  }
  const detail = (state.categoryDetail ?? "").trim();
  return {
    project: "",
    category: state.categoryName ?? "",
    description: detail !== "" ? detail : state.description ?? "",
  };
}

/**
 * Single shared save routine used by both the OCR-callback and text flows.
 * Downloads pages, uploads to the shared receipts folder (PDF or photo fallback), and appends
 * one row to the centralized receipts sheet with project/category per the branch rules.
 */
export async function finalizeSave(ctx: Context, bot: Bot, state: ConversationState): Promise<void> {
  const telegramId = state.telegramId;

  try {
    await ctx.reply("⏳ Сохраняю...");

    // 1. Download all pages (prefer multi-page photoFileIds; fall back to legacy photoFileId).
    const fileIds: string[] =
      state.photoFileIds && state.photoFileIds.length > 0
        ? state.photoFileIds
        : state.photoFileId
        ? [state.photoFileId]
        : [];

    const files: Array<{ buffer: Buffer; mimeType: string }> = [];
    for (const fileId of fileIds) {
      const { buffer, mimeType } = await downloadFile(bot, fileId);
      files.push({ buffer, mimeType });
    }

    // 2. Upload to the shared receipts folder (PDF preferred, photo fallback).
    let fileLink: string;
    try {
      const links = await uploadReceiptArtifact(
        state.storeName ?? "receipt",
        state.sum ?? 0,
        files,
        { telegramId },
      );
      fileLink = links.join("\n");
    } catch (err: any) {
      logger.error("Drive upload failed", { telegramId, error: err.message });
      if (state.sessionId) {
        await sessionLog.finalizeFailed(state.sessionId, err, {
          step: state.step,
          sum: state.sum,
          photoFileIds: state.photoFileIds,
        });
      }
      await ctx.reply("❌ Ошибка загрузки файла. Попробуйте ещё раз (/start)");
      return;
    }

    // 3. Append one row to the centralized receipts sheet.
    const today = new Date().toISOString().split("T")[0];
    const addedBy = [ctx.from!.first_name, ctx.from!.last_name].filter(Boolean).join(" ");
    const { project, category, description } = receiptRowFields(state);

    // Sum note: mismatch (OCR vs entered) or manual entry after OCR failure.
    let sumNote: string | undefined;
    if (state.ocrGrossAmount !== undefined && state.ocrGrossAmount !== null) {
      if (state.sum !== state.ocrGrossAmount) {
        sumNote = `⚠️ OCR: ${state.ocrGrossAmount}, введено: ${state.sum} — подтверждено сотрудником`;
      }
    } else if (state.photoFileIds && state.photoFileIds.length > 0 && !state.ocrDescription) {
      sumNote = "⚠️ Данные введены вручную (OCR не распознал)";
    }

    try {
      await withRetry(() =>
        appendReceiptRowCentral({
          date: today,
          project,
          category,
          storeName: state.storeName ?? "",
          description,
          sum: state.sum ?? 0,
          fileLink,
          addedBy,
          sumNote,
        }),
      );
    } catch (err: any) {
      logger.error("Central sheet write failed", { telegramId, error: err.message, stack: err.stack?.slice(0, 500) });
      if (state.sessionId) {
        await sessionLog.finalizeFailed(state.sessionId, err, {
          step: state.step,
          sum: state.sum,
          photoFileIds: state.photoFileIds,
          photoLinks: [fileLink],
        });
      }
      await ctx.reply("❌ Ошибка записи в таблицу. Файл загружен, но строка не добавлена. Попробуйте /start");
      return;
    }

    // 4. Success.
    await clearState(telegramId);
    if (state.sessionId) {
      await sessionLog.finalizeSuccess(state.sessionId, {
        step: ConversationStep.SAVING,
        sum: state.sum,
        photoFileIds: state.photoFileIds,
        photoLinks: [fileLink],
      });
    }
    const tag = state.isMaterial ? `Материал / ${project}` : category;
    await ctx.reply(
      `✅ Записал: ${tag}, ${state.sum ?? 0} PLN, ${state.storeName ?? ""}, ${description}\n\nМожете отправить следующий чек (/start)`,
    );

    logger.info("Receipt saved (central)", {
      telegramId,
      material: state.isMaterial,
      project,
      category,
      sum: state.sum,
      store: state.storeName,
      pages: state.photoFileIds?.length,
    });
  } catch (err: any) {
    logger.error("finalizeSave error", { telegramId, error: err.message });
    if (state.sessionId) await sessionLog.finalizeFailed(state.sessionId, err, { step: state.step });
    await ctx.reply("⚠️ Произошла ошибка. Попробуйте позже или напишите /start");
  }
}
