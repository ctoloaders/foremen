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

  // Show project selection (use index as callback_data to avoid 64-byte limit)
  const keyboard = new InlineKeyboard();
  const maxToShow = Math.min(projects.length, 50);
  for (let i = 0; i < maxToShow; i++) {
    // Truncate label to 30 chars to ensure button text fits
    const name = projects[i].name;
    const label = name.length > 30 ? name.slice(0, 30) + "…" : name;
    keyboard.text(label, `p:${i}`).row();
  }

  // Set state to SELECT_PROJECT — store projects list temporarily
  const state = emptyState(telegramId);
  state.step = ConversationStep.SELECT_PROJECT;
  await setState(state);

  // Store projects in a module-level cache for this user (needed for callback resolution)
  projectsCache.set(telegramId, projects);

  await ctx.reply(
    `Привет, ${worker.name}! (${worker.role})\nВыберите проект:`,
    { reply_markup: keyboard }
  );
}

// Cache projects per user for callback resolution (cleared after selection)
const projectsCache = new Map<number, any[]>();

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
