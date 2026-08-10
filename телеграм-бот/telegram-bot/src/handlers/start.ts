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
  const keyboard = new InlineKeyboard();
  for (const project of projects) {
    keyboard.text(project.name, `project:${project.name}`).row();
  }

  // Set state to SELECT_PROJECT
  const state = emptyState(telegramId);
  state.step = ConversationStep.SELECT_PROJECT;
  await setState(state);

  await ctx.reply(
    `Привет, ${worker.name}! (${worker.role})\nВыберите проект:`,
    { reply_markup: keyboard }
  );
}

export async function handleProjectSelection(ctx: Context) {
  const telegramId = ctx.from?.id;
  if (!telegramId) return;

  const data = ctx.callbackQuery?.data;
  if (!data?.startsWith("project:")) return;

  const projectName = data.slice("project:".length);

  // Get project details
  const worker = await getWorker(telegramId);
  if (!worker) return;

  const projects = await getProjectsForWorker(worker);
  const project = projects.find(p => p.name === projectName);
  if (!project) {
    await ctx.answerCallbackQuery({ text: "Проект не найден" });
    return;
  }

  // Update state
  const state = emptyState(telegramId);
  state.step = ConversationStep.AWAIT_PHOTO;
  state.projectName = project.name;
  state.projectDriveUrl = project.googleDriveUrl;
  state.projectSheetsUrl = project.receiptsUrl || project.googleSheetsUrl;
  await setState(state);

  await ctx.answerCallbackQuery();
  await ctx.editMessageText(`Проект: ${project.name}\n\nПришлите фото чека 📸`);

  logger.info("Project selected", { telegramId, project: project.name });
}
