import { Context, InlineKeyboard } from "grammy";
import { getWorker, getProjectsForWorker } from "../services/sheets.js";
import { setState } from "../state/store.js";
import { ConversationStep, emptyState } from "../state/machine.js";
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

  // Set state to SELECT_PROJECT
  const state = emptyState(telegramId);
  state.step = ConversationStep.SELECT_PROJECT;
  await setState(state);

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

  // Update state
  const state = emptyState(telegramId);
  state.step = ConversationStep.AWAIT_PHOTO;
  state.projectName = project.name;
  state.projectDriveUrl = project.googleDriveUrl;
  state.projectSheetsUrl = project.googleSheetsUrl;
  await setState(state);

  await ctx.answerCallbackQuery();
  const cancelKb = new InlineKeyboard().text("❌ Отмена", "cancel");
  await ctx.editMessageText(`Проект: ${project.name}\n\nПришлите фото чека 📸`, { reply_markup: cancelKb });

  logger.info("Project selected", { telegramId, project: project.name });
}
