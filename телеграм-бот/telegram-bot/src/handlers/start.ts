import { Context, InlineKeyboard } from "grammy";
import { randomUUID } from "crypto";
import { getWorker, getProjectsForWorker } from "../services/sheets.js";
import { setState, getState } from "../state/store.js";
import { ConversationStep, emptyState } from "../state/machine.js";
import { sessionLog } from "../services/session-log.js";
import { logger } from "../utils/logger.js";

export async function handleStart(ctx: Context) {
  const telegramId = ctx.from?.id;
  if (!telegramId) return;

  // Check worker registry
  const worker = await getWorker(telegramId);
  if (!worker) {
    await ctx.reply(
      `Доступ запрещён. Обратитесь к администратору.\nВаш Telegram ID: ${telegramId}`
    );
    return;
  }

  logger.info("Worker authenticated", { telegramId, name: worker.name, role: worker.role });
  
  // Get projects
  const projects = await getProjectsForWorker(worker);
  if (projects.length === 0) {
    await ctx.reply("У вас нет активных проектов. Обратитесь к менеджеру.");
    return;
  }

  // Show project selection
  // callback_data limit: 64 bytes. Use index to avoid UTF-8 overflow.
  const keyboard = new InlineKeyboard();
  for (let i = 0; i < projects.length; i++) {
    keyboard.text(projects[i].name, `p${i}`).row();
  }

  // Start a new session (fresh id). Any prior unfinished session row remains
  // in_progress (abandoned) — we never finalize it here.
  const sessionId = randomUUID();

  // Set state to SELECT_PROJECT
  const state = emptyState(telegramId);
  state.step = ConversationStep.SELECT_PROJECT;
  state.sessionId = sessionId;
  await setState(state);

  // Session Log: create the in_progress row
  await sessionLog.startSession({
    sessionId,
    telegramId,
    workerName: worker.name,
    role: worker.role,
    step: ConversationStep.SELECT_PROJECT,
  });

  // Cache projects for callback resolution
  userProjectsCache.set(telegramId, projects);

  await ctx.reply(
    `Привет, ${worker.name}! (${worker.role})\nВыберите проект:`,
    { reply_markup: keyboard }
  );
}

// In-memory cache: telegramId -> projects list (for resolving index from callback)
const userProjectsCache = new Map<number, any[]>();

export async function handleProjectSelection(ctx: Context) {
  const telegramId = ctx.from?.id;
  if (!telegramId) return;

  const data = ctx.callbackQuery?.data;
  if (!data?.startsWith("p")) return;

  const index = parseInt(data.slice(1));
  if (isNaN(index)) return;

  // Resolve project from cache or re-fetch
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

  // Carry the session id forward from the existing state (emptyState omits it).
  const prev = await getState(telegramId);
  const sessionId = prev?.sessionId;

  // Update state
  const state = emptyState(telegramId);
  state.step = ConversationStep.AWAIT_PHOTO;
  state.sessionId = sessionId;
  state.projectName = project.name;
  state.projectDriveUrl = project.googleDriveUrl;
  state.projectSheetsUrl = project.googleSheetsUrl;
  await setState(state);

  // Session Log: record the selected project
  if (sessionId) {
    await sessionLog.updateSession(sessionId, {
      step: ConversationStep.AWAIT_PHOTO,
      projectName: project.name,
      projectDriveUrl: project.googleDriveUrl,
      projectSheetsUrl: project.googleSheetsUrl,
    });
  }

  await ctx.answerCallbackQuery();
  const cancelKb = new InlineKeyboard().text("❌ Отмена", "cancel");
  await ctx.editMessageText(
    `Проект: ${project.name}\n\nПришлите фото чека 📸\n💡 Для лучшего качества отправьте как файл (без сжатия).`,
    { reply_markup: cancelKb },
  );

  logger.info("Project selected", { telegramId, project: project.name });
}
