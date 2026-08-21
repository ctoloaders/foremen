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

  // Show project selection as numbered list + ask to type number
  // (Inline keyboard causes BUTTON_DATA_INVALID with many projects)
  const maxToShow = Math.min(projects.length, 50);
  let message = `Привет, ${worker.name}! (${worker.role})\nВыберите проект (введите номер):\n\n`;
  for (let i = 0; i < maxToShow; i++) {
    message += `${i + 1}. ${projects[i].name}\n`;
  }
  if (projects.length > maxToShow) {
    message += `\n... и ещё ${projects.length - maxToShow}`;
  }

  // Set state to SELECT_PROJECT
  const state = emptyState(telegramId);
  state.step = ConversationStep.SELECT_PROJECT;
  await setState(state);

  // Store projects in cache for number resolution
  projectsCache.set(telegramId, projects);

  const cancelKb = new InlineKeyboard().text("❌ Отмена", "cancel");
  await ctx.reply(message, { reply_markup: cancelKb });
}

// Cache projects per user for number selection (cleared after selection)
export const projectsCache = new Map<number, any[]>();

export async function handleProjectSelection(ctx: Context) {
  const telegramId = ctx.from?.id;
  if (!telegramId) return;

  const data = ctx.callbackQuery?.data;
  if (!data?.startsWith("p:")) return;

  const index = parseInt(data.slice("p:".length));
  
  // Get projects from cache or re-fetch
  let projects = projectsCache.get(telegramId);
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

  // Clear cache
  projectsCache.delete(telegramId);

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
