package com.foremen.qa.report;

import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the collected {@link ReportModel.Run} into the client-facing demo report and the
 * machine/standard outputs (Requirement 10.4, 10.5, 10.6, 10.8).
 *
 * <p>Given the run-scoped output folder, it writes:
 * <ul>
 *   <li>{@code report.json} — the structured data model (also the source for the MD tables);</li>
 *   <li>{@code index.html} — the run summary + navigation grouped by source spec ({@code @FOR-0N})
 *       and feature;</li>
 *   <li>{@code features/<slug>.html} — one demo page per feature: scenarios → ordered steps with
 *       plain-language description, expected/actual, a status chip, and the step screenshot; failing
 *       steps are highlighted and show the error + a trace link;</li>
 *   <li>{@code assets/style.css} — self-contained styling so the report opens in any browser;</li>
 *   <li>{@code run-report.md} — the steering-standard MD run-report tables.</li>
 * </ul>
 *
 * <p>The HTML is fully self-contained (no runtime server, no CDN): screenshots are referenced by
 * relative path under {@code assets/}. All report text has already been redacted for secrets by the
 * plugin, so the writer only needs to HTML-escape for safe rendering (Requirement 10.10).
 */
public final class ReportWriter {

    private final Path runFolder;

    public ReportWriter(Path runFolder) {
        this.runFolder = runFolder;
    }

    /** Write every report artifact for {@code run} into the run folder. */
    public void write(ReportModel.Run run) throws IOException {
        Files.createDirectories(runFolder);
        Files.createDirectories(runFolder.resolve("features"));
        Files.createDirectories(runFolder.resolve("assets"));

        writeJson(run);
        writeCss();
        writeIndex(run);
        for (ReportModel.Feature feature : run.features) {
            writeFeaturePage(run, feature);
        }
        writeMarkdown(run);

        System.out.println("[ReportWriter] Demo report written to: "
                + runFolder.resolve("index.html").toAbsolutePath());
    }

    // ---- report.json ----

    private void writeJson(ReportModel.Run run) throws IOException {
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(run);
        Files.writeString(runFolder.resolve("report.json"), json, StandardCharsets.UTF_8);
    }

    // ---- index.html ----

    private void writeIndex(ReportModel.Run run) throws IOException {
        StringBuilder sb = new StringBuilder();
        htmlHead(sb, "QA Smoke Report — " + esc(run.runId), ".");
        sb.append("<body>\n");
        sb.append("<header class=\"topbar\"><h1>Foremen QA — Smoke Demo Report</h1></header>\n");
        sb.append("<main class=\"container\">\n");

        // Run summary card.
        sb.append("<section class=\"card summary\">\n<h2>Run summary</h2>\n");
        sb.append("<div class=\"totals\">\n");
        chip(sb, "Features", Integer.toString(run.totalFeatures), "neutral");
        chip(sb, "Scenarios", Integer.toString(run.totalScenarios), "neutral");
        chip(sb, "Passed", Integer.toString(run.passedScenarios), "pass");
        chip(sb, "Failed", Integer.toString(run.failedScenarios),
                run.failedScenarios > 0 ? "fail" : "neutral");
        chip(sb, "Skipped", Integer.toString(run.skippedScenarios), "skip");
        sb.append("</div>\n");
        sb.append("<table class=\"meta\">\n");
        metaRow(sb, "Run id", run.runId);
        metaRow(sb, "Started", run.startedAt);
        metaRow(sb, "Finished", run.finishedAt);
        metaRow(sb, "Frontend", run.frontendUrl);
        metaRow(sb, "API", run.apiUrl);
        metaRow(sb, "Browser", (run.browser == null ? "" : run.browser)
                + (run.headed ? " (headed)" : " (headless)"));
        metaRow(sb, "Screenshots", run.shotsMode + (run.fullPageShots ? ", full-page" : ", viewport"));
        sb.append("</table>\n</section>\n");

        // Navigation grouped by source spec (@FOR-0N) then feature.
        Map<String, List<ReportModel.Feature>> bySpec = groupBySpec(run);
        sb.append("<section class=\"card\">\n<h2>Features by spec</h2>\n");
        for (Map.Entry<String, List<ReportModel.Feature>> entry : bySpec.entrySet()) {
            sb.append("<h3 class=\"spec\">").append(esc(entry.getKey())).append("</h3>\n<ul class=\"features\">\n");
            for (ReportModel.Feature f : entry.getValue()) {
                String status = featureStatusClass(f);
                sb.append("<li>")
                        .append("<span class=\"dot ").append(status).append("\"></span>")
                        .append("<a href=\"features/").append(esc(f.slug)).append(".html\">")
                        .append(esc(f.name)).append("</a>")
                        .append(" <span class=\"muted\">(")
                        .append(f.scenarios.size()).append(" scenario")
                        .append(f.scenarios.size() == 1 ? "" : "s").append(")</span>")
                        .append("</li>\n");
            }
            sb.append("</ul>\n");
        }
        sb.append("</section>\n");

        sb.append("</main>\n</body>\n</html>\n");
        Files.writeString(runFolder.resolve("index.html"), sb.toString(), StandardCharsets.UTF_8);
    }

    // ---- feature page ----

    private void writeFeaturePage(ReportModel.Run run, ReportModel.Feature feature) throws IOException {
        StringBuilder sb = new StringBuilder();
        htmlHead(sb, esc(feature.name), "..");
        sb.append("<body>\n");
        sb.append("<header class=\"topbar\"><a class=\"back\" href=\"../index.html\">← Back to summary</a>")
                .append("<h1>").append(esc(feature.name)).append("</h1>")
                .append("<span class=\"spec-badge\">").append(esc(feature.sourceTag)).append("</span>")
                .append("</header>\n");
        sb.append("<main class=\"container\">\n");

        if (feature.description != null && !feature.description.isBlank()) {
            sb.append("<p class=\"feature-intro\">").append(esc(feature.description)).append("</p>\n");
        }

        for (ReportModel.Scenario scenario : feature.scenarios) {
            sb.append("<section class=\"card scenario\">\n");
            sb.append("<h2>").append(statusChipInline(scenario.status)).append(" ")
                    .append(esc(scenario.name)).append("</h2>\n");
            if (scenario.intent != null && !scenario.intent.isBlank()
                    && !scenario.intent.equals(scenario.name)) {
                sb.append("<p class=\"scenario-intent\"><b>Что проверяем:</b> ")
                        .append(esc(scenario.intent)).append("</p>\n");
            }
            if (!scenario.tags.isEmpty()) {
                sb.append("<div class=\"tags\">");
                for (String tag : scenario.tags) {
                    sb.append("<span class=\"tag\">").append(esc(tag)).append("</span>");
                }
                sb.append("</div>\n");
            }
            sb.append("<ol class=\"steps\">\n");
            int i = 0;
            for (ReportModel.Step step : scenario.steps) {
                i++;
                boolean problem = step.status.isProblem();
                sb.append("<li class=\"step ").append(problem ? "step-fail" : "").append("\">\n");
                sb.append("<div class=\"step-head\">")
                        .append("<span class=\"num\">").append(i).append("</span>")
                        .append(statusChipInline(step.status))
                        .append("<span class=\"step-text\">");
                if (step.demoFrame) {
                    sb.append("<span class=\"demo-badge\">demo</span> ");
                }
                // Primary human-readable line: the description. Fall back to the Gherkin text.
                String primary = step.description != null && !step.description.isBlank()
                        ? step.description
                        : (step.keyword == null ? "" : step.keyword) + step.text;
                sb.append("<span class=\"step-desc\">").append(esc(primary)).append("</span>");
                // Secondary muted line: the raw Gherkin keyword + text (skipped for demo frames).
                if (!step.demoFrame) {
                    sb.append("<span class=\"step-gherkin\">")
                            .append("<b>").append(esc(step.keyword == null ? "" : step.keyword)).append("</b>")
                            .append(esc(step.text))
                            .append("</span>");
                }
                sb.append("</span>")
                        .append("</div>\n");
                if (!step.demoFrame) {
                    sb.append("<div class=\"expected-actual\">")
                            .append("<span class=\"ea\"><b>Ожидаемый результат:</b> ").append(esc(step.expected)).append("</span>")
                            .append("<span class=\"ea\"><b>Факт:</b> ").append(esc(step.actual)).append("</span>")
                            .append("</div>\n");
                }
                if (step.error != null) {
                    sb.append("<pre class=\"error\">").append(esc(step.error)).append("</pre>\n");
                }
                if (step.screenshot != null) {
                    sb.append("<a class=\"shot\" href=\"../").append(esc(step.screenshot))
                            .append("\" target=\"_blank\">")
                            .append("<img loading=\"lazy\" src=\"../").append(esc(step.screenshot))
                            .append("\" alt=\"").append(esc(step.text)).append("\"/>")
                            .append("</a>\n");
                }
                sb.append("</li>\n");
            }
            sb.append("</ol>\n");

            // Trace link (Playwright writes traces per scenario when QA_TRACE=true).
            if (scenario.status.isProblem()) {
                sb.append("<p class=\"trace\">Diagnostics: a Playwright trace, when recorded, is under ")
                        .append("<code>build/qa-report/traces/</code>.</p>\n");
            }
            sb.append("</section>\n");
        }

        sb.append("</main>\n</body>\n</html>\n");
        Files.writeString(runFolder.resolve("features").resolve(feature.slug + ".html"),
                sb.toString(), StandardCharsets.UTF_8);
    }

    // ---- Markdown run-report (steering standard) ----

    private void writeMarkdown(ReportModel.Run run) throws IOException {
        StringBuilder md = new StringBuilder();
        md.append("# QA Smoke — MD Run Report\n\n");
        md.append("- Run-id: `").append(run.runId).append("`\n");
        md.append("- Started: ").append(run.startedAt).append("  Finished: ").append(run.finishedAt).append("\n");
        md.append("- Environment: frontend `").append(nz(run.frontendUrl)).append("`, API `")
                .append(nz(run.apiUrl)).append("`, browser `").append(nz(run.browser))
                .append(run.headed ? " (headed)" : " (headless)").append("`\n\n");
        md.append("Итог: ").append(run.passedScenarios).append(" пройдено / ")
                .append(run.failedScenarios).append(" провалено / ")
                .append(run.skippedScenarios).append(" пропущено")
                .append(" (сценариев: ").append(run.totalScenarios)
                .append(", фич: ").append(run.totalFeatures).append(").\n\n");

        for (ReportModel.Feature feature : run.features) {
            md.append("## ").append(feature.sourceTag).append(" — ").append(feature.name).append("\n\n");
            if (feature.description != null && !feature.description.isBlank()) {
                md.append(feature.description).append("\n\n");
            }
            for (ReportModel.Scenario scenario : feature.scenarios) {
                md.append("### ").append(scenario.name)
                        .append(" — ").append(statusText(scenario.status)).append("\n\n");
                if (scenario.intent != null && !scenario.intent.isBlank()
                        && !scenario.intent.equals(scenario.name)) {
                    md.append("**Что проверяем:** ").append(scenario.intent).append("\n\n");
                }
                md.append("| # | Шаг | Что делаем | Ожидаемый результат | Факт | Статус |\n");
                md.append("|---|-----|------------|---------------------|------|--------|\n");
                int i = 0;
                for (ReportModel.Step step : scenario.steps) {
                    i++;
                    String text = (step.keyword == null ? "" : step.keyword) + step.text;
                    md.append("| ").append(i)
                            .append(" | ").append(mdCell(text))
                            .append(" | ").append(mdCell(step.demoFrame ? "—" : step.description))
                            .append(" | ").append(mdCell(step.demoFrame ? "—" : step.expected))
                            .append(" | ").append(mdCell(step.demoFrame ? "(демо-кадр)" : step.actual))
                            .append(" | ").append(statusEmoji(step.status))
                            .append(" |\n");
                }
                md.append("\n");
            }
        }
        Files.writeString(runFolder.resolve("run-report.md"), md.toString(), StandardCharsets.UTF_8);
    }

    // ---- CSS ----

    private void writeCss() throws IOException {
        String css = """
                :root{--bg:#0f1115;--card:#171a21;--ink:#e6e6e6;--muted:#9aa4b2;--line:#262b34;
                  --pass:#2ec26a;--fail:#e5484d;--skip:#f5a623;--accent:#4f8cff}
                *{box-sizing:border-box}
                body{margin:0;font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;
                  background:var(--bg);color:var(--ink);line-height:1.5}
                .topbar{display:flex;align-items:center;gap:1rem;padding:1rem 1.5rem;
                  background:var(--card);border-bottom:1px solid var(--line);position:sticky;top:0;z-index:5}
                .topbar h1{font-size:1.1rem;margin:0}
                .topbar .back{color:var(--accent);text-decoration:none;font-size:.9rem}
                .spec-badge,.spec{color:var(--accent);font-weight:600}
                .container{max-width:1000px;margin:1.5rem auto;padding:0 1rem}
                .card{background:var(--card);border:1px solid var(--line);border-radius:10px;
                  padding:1.2rem 1.4rem;margin-bottom:1.2rem}
                h2{margin:.2rem 0 1rem;font-size:1.05rem}
                h3.spec{margin:1rem 0 .4rem}
                .totals{display:flex;gap:.6rem;flex-wrap:wrap;margin-bottom:1rem}
                .chip{border-radius:999px;padding:.35rem .8rem;font-size:.85rem;border:1px solid var(--line)}
                .chip .n{font-weight:700;margin-left:.3rem}
                .chip.pass{background:rgba(46,194,106,.12);color:var(--pass)}
                .chip.fail{background:rgba(229,72,77,.14);color:var(--fail)}
                .chip.skip{background:rgba(245,166,35,.12);color:var(--skip)}
                .chip.neutral{color:var(--muted)}
                table.meta{border-collapse:collapse;width:100%;font-size:.9rem}
                table.meta td{padding:.25rem .5rem;border-bottom:1px solid var(--line)}
                table.meta td:first-child{color:var(--muted);width:9rem}
                ul.features{list-style:none;padding:0;margin:0}
                ul.features li{padding:.35rem 0;display:flex;align-items:center;gap:.5rem}
                ul.features a{color:var(--ink);text-decoration:none}
                ul.features a:hover{color:var(--accent)}
                .muted{color:var(--muted);font-size:.85rem}
                .dot{width:.6rem;height:.6rem;border-radius:50%;display:inline-block;background:var(--muted)}
                .dot.pass{background:var(--pass)}.dot.fail{background:var(--fail)}.dot.skip{background:var(--skip)}
                .tags{margin:-.4rem 0 .8rem}
                .tag{display:inline-block;font-size:.75rem;color:var(--muted);border:1px solid var(--line);
                  border-radius:6px;padding:.1rem .4rem;margin-right:.3rem}
                ol.steps{list-style:none;padding:0;margin:0;counter-reset:none}
                .step{border-top:1px solid var(--line);padding:1rem 0}
                .step:first-child{border-top:none}
                .step-fail{background:rgba(229,72,77,.06);border-radius:8px;padding:1rem;margin:.4rem 0}
                .step-head{display:flex;align-items:center;gap:.6rem;flex-wrap:wrap}
                .num{color:var(--muted);font-variant-numeric:tabular-nums;min-width:1.4rem}
                .step-text{flex:1;display:flex;flex-direction:column;gap:.15rem}
                .step-desc{color:var(--ink)}
                .step-gherkin{color:var(--muted);font-size:.82rem}
                .step-gherkin b{color:var(--muted)}
                p.feature-intro{color:var(--ink);margin:.2rem 0 1.2rem;font-size:.95rem}
                p.scenario-intent{color:var(--muted);font-size:.9rem;margin:-.6rem 0 .8rem}
                p.scenario-intent b{color:var(--ink)}
                .status{font-size:.72rem;font-weight:700;border-radius:6px;padding:.1rem .45rem;text-transform:uppercase}
                .status.pass{background:rgba(46,194,106,.15);color:var(--pass)}
                .status.fail{background:rgba(229,72,77,.16);color:var(--fail)}
                .status.skip{background:rgba(245,166,35,.15);color:var(--skip)}
                .status.other{background:rgba(154,164,178,.15);color:var(--muted)}
                .demo-badge{font-size:.68rem;background:rgba(79,140,255,.15);color:var(--accent);
                  border-radius:5px;padding:.05rem .35rem;text-transform:uppercase;font-weight:700}
                .expected-actual{display:flex;gap:1.5rem;flex-wrap:wrap;margin:.4rem 0 .6rem 2rem;
                  font-size:.85rem;color:var(--muted)}
                .expected-actual b{color:var(--ink);font-weight:600}
                pre.error{background:#000;color:#ff9b9b;border:1px solid var(--fail);border-radius:8px;
                  padding:.8rem;overflow:auto;font-size:.8rem;margin:.4rem 0 .6rem 2rem;white-space:pre-wrap}
                .shot{display:block;margin:.4rem 0 0 2rem;max-width:640px}
                .shot img{max-width:100%;border:1px solid var(--line);border-radius:8px;display:block}
                p.trace{color:var(--muted);font-size:.85rem;margin-left:2rem}
                code{background:#000;border:1px solid var(--line);border-radius:4px;padding:.05rem .3rem}
                """;
        Files.writeString(runFolder.resolve("assets").resolve("style.css"), css, StandardCharsets.UTF_8);
    }

    // ---- small html/markdown helpers ----

    private static void htmlHead(StringBuilder sb, String title, String assetsPrefix) {
        sb.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n")
                .append("<meta charset=\"utf-8\"/>\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>\n")
                .append("<title>").append(title).append("</title>\n")
                .append("<link rel=\"stylesheet\" href=\"").append(assetsPrefix).append("/assets/style.css\"/>\n")
                .append("</head>\n");
    }

    private static void chip(StringBuilder sb, String label, String value, String cls) {
        sb.append("<span class=\"chip ").append(cls).append("\">")
                .append(esc(label)).append("<span class=\"n\">").append(esc(value)).append("</span></span>\n");
    }

    private static void metaRow(StringBuilder sb, String k, String v) {
        sb.append("<tr><td>").append(esc(k)).append("</td><td>").append(esc(nz(v))).append("</td></tr>\n");
    }

    private static String statusChipInline(ReportModel.Status status) {
        String cls = switch (status) {
            case PASSED -> "pass";
            case FAILED -> "fail";
            case SKIPPED -> "skip";
            default -> "other";
        };
        return "<span class=\"status " + cls + "\">" + statusText(status) + "</span>";
    }

    private static Map<String, List<ReportModel.Feature>> groupBySpec(ReportModel.Run run) {
        Map<String, List<ReportModel.Feature>> map = new LinkedHashMap<>();
        for (ReportModel.Feature f : run.features) {
            map.computeIfAbsent(f.sourceTag == null ? "@OTHER" : f.sourceTag, k -> new java.util.ArrayList<>())
                    .add(f);
        }
        return map;
    }

    private static String featureStatusClass(ReportModel.Feature f) {
        boolean anyFail = false;
        boolean anySkip = false;
        for (ReportModel.Scenario s : f.scenarios) {
            if (s.status.isProblem()) {
                anyFail = true;
            } else if (s.status == ReportModel.Status.SKIPPED) {
                anySkip = true;
            }
        }
        if (anyFail) {
            return "fail";
        }
        if (anySkip) {
            return "skip";
        }
        return "pass";
    }

    private static String statusText(ReportModel.Status status) {
        return switch (status) {
            case PASSED -> "passed";
            case FAILED -> "failed";
            case SKIPPED -> "skipped";
            case PENDING -> "pending";
            case UNDEFINED -> "undefined";
            case AMBIGUOUS -> "ambiguous";
            default -> "unknown";
        };
    }

    private static String statusEmoji(ReportModel.Status status) {
        return switch (status) {
            case PASSED -> "✅";
            case FAILED -> "❌";
            case SKIPPED -> "⏭️";
            default -> "⚠️";
        };
    }

    private static String mdCell(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("|", "\\|").replace("\n", " ").trim();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
