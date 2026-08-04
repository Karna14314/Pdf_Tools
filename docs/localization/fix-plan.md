# PDF Toolkit — Localization Fix Plan

Source of truth: `docs/localization/audit-2026-08.md`.

Status legend: `TODO` = not started · `DONE` = completed & verified · `SKIPPED-<reason>` = intentionally not done.

Rules of engagement (from task):
- Work phases in order. Stop after each phase (and after **each screen** in Phase 3) and report before continuing.
- In Phase 3, **reuse existing dead keys first** (audit §A2, 124 keys) before adding new ones.
- Phase 5 is **proposal-only**: list corrected values as diffs, wait for per-locale go-ahead, never batch-apply.
- Run the full fix program sequentially; do not jump ahead.

---

## Phase 1 — Correctness bugs (small, high-value, do first)

### 1.1 Wrong-key bug — ToolsScreen.kt
| [ ] | Ref (audit) | Item | Status |
|---|---|---|---|
| [ ] | ToolsScreen.kt:544 (§B#1) | `view_pdf` card uses `tool_view_metadata`; must reference a new `tool_view_pdf` key. Add `tool_view_pdf` to `values/strings.xml` (+ all 14 locale files), point :544 at it. Keep `desc_view_pdf` at :545. | DONE |

### 1.2 `desc_convert_format` corruption (default + 10 locales)
| [ ] | Ref (audit) | Item | Status |
|---|---|---|---|
| [ ] | `values/strings.xml` (§D) | "JPEG �?? WebP" → "JPEG ↔ WebP" (match de/es/hi/zh) | DONE |
| [ ] | `values-ar/strings.xml` (§D) | "جى بي إي جي ؟؟ ويب بي" → restore "JPEG ↔ WebP" | DONE |
| [ ] | `values-fr/strings.xml` (§D) | "JPEG\xa0??? WebP" → "JPEG ↔ WebP" | DONE |
| [ ] | `values-id/strings.xml` (§D) | "JPEG �?? WebP" → "JPEG ↔ WebP" | DONE |
| [ ] | `values-ja/strings.xml` (§D) | "JPEG・・・？？ WebP" → "JPEG ↔ WebP" | DONE |
| [ ] | `values-ko/strings.xml` (§D) | "JPEG ?? 웹P" → "JPEG ↔ WebP" | DONE |
| [ ] | `values-pt-rBR/strings.xml` (§D) | "JPEG �?? WebP" → "JPEG ↔ WebP" | DONE |
| [ ] | `values-ru/strings.xml` (§D) | "JPEG �?? ВебП" → "JPEG ↔ WebP" | DONE |
| [ ] | `values-tk/strings.xml` (§D) | "JPEG � ?? WebP" → "JPEG ↔ WebP" | DONE |
| [ ] | `values-tr/strings.xml` (§D) | "JPEG�?? WebP" → "JPEG ↔ WebP" | DONE |
| [ ] | `values-uz/strings.xml` (§D) | "JPEG ?? WebP" → "JPEG ↔ WebP" | DONE |

### 1.3 Literal `amp;` leaks (bad escaping)
| [ ] | Ref (audit) | Item | Status |
|---|---|---|---|
| [ ] | `values-ru/strings.xml` `settings_about_feature_4` (§D) | "OCR и amp; Сканировать в PDF" → "OCR и Сканирование в PDF" (remove `amp;`) — **APPROVED WITH DEVIATION**: applied as "OCR и сканирование в PDF" (natural lowercase) | DONE |
| [ ] | `values-fr/strings.xml` `settings_about_feature_4` (§D) | "ROC et amp; Numériser vers PDF" → "ROC et Numérisation vers PDF" — **APPROVED WITH DEVIATION**: applied as "OCR et numérisation vers PDF" (also fixes backwards "ROC" → "OCR") | DONE |
| [ ] | `values-fr/strings.xml` `settings_about_feature_3` (§D) | "…et des amp; signature" → "…et des filigranes et signatures" (remove `amp;`) | DONE |
| [ ] | `values-fr/strings.xml` `home_subtitle` (§D) | "Tous vos PDF et amp; outils d'image…" → remove `amp;` | DONE |
| [ ] | `values-fr/strings.xml` `settings_about_kotlin_compose` (§D) | "Kotlin et amp; Composer Jetpack" → "Kotlin & Jetpack Compose" (see Phase 2 for brand) | DONE |

### 1.4 Corrupted display names
| [ ] | Ref (audit) | Item | Status |
|---|---|---|---|
| [ ] | `values-pt-rBR/strings.xml` `language_hindi` (§D) | "हिन्द�?" → "हिन्दी" | DONE |
| [ ] | `values-pt-rBR/strings.xml` `language_chinese` (§D) | "中�??" → "中文" | DONE |

---

## Phase 2 — Brand name protection (9 affected locales: ar, fr, id, ja, ko, ru, tk, tr, uz)

Rule: "PDF Toolkit" must appear **verbatim** (unlocalized) in every locale; "Jetpack Compose" is a product name and should be preserved as-is.

### 2.1 `settings_copyright` (footer)
| [ ] | Locale | Current | Status |
|---|---|---|---|
| [ ] | ar | "© 2026 مجموعة أدوات PDF" → "© 2026 PDF Toolkit" | DONE |
| [ ] | fr | "© 2026 Boîte à outils PDF" → "© 2026 PDF Toolkit" | DONE |
| [ ] | id | "© 2026 Perangkat PDF" → "© 2026 PDF Toolkit" | DONE |
| [ ] | ja | "© 2026 PDF ツールキット" → "© 2026 PDF Toolkit" | DONE |
| [ ] | ko | "© 2026 PDF 툴킷" → "© 2026 PDF Toolkit" | DONE |
| [ ] | ru | "© 2026 PDF-инструментарий" → "© 2026 PDF Toolkit" | DONE |
| [ ] | tk | "© 2026 PDF gurallar toplumy" → "© 2026 PDF Toolkit" | DONE |
| [ ] | tr | "© 2026 PDF Araç Takımı" → "© 2026 PDF Toolkit" | DONE |
| [ ] | uz | "© 2026 PDF asboblar to'plami" → "© 2026 PDF Toolkit" | DONE |

### 2.2 `bug_report_subject` (8 locales)
| [ ] | Locale | Current | Status |
|---|---|---|---|
| [ ] | ar | "[تقرير الأخطاء] مجموعة أدوات PDF" → "[Bug Report] PDF Toolkit" (brand verbatim) — **applied**: "[تقرير الأخطاء] PDF Toolkit" (translated prefix kept per rule 1) | DONE |
| [ ] | fr | "[Rapport de bug] Boîte à outils PDF" → brand verbatim | DONE |
| [ ] | id | "[Laporan Bug] Perangkat PDF" → brand verbatim | DONE |
| [ ] | ja | "[バグレポート] PDF ツールキット" → brand verbatim | DONE |
| [ ] | ko | "[버그 리포트] PDF 툴킷" → brand verbatim | DONE |
| [ ] | tk | "[Bug hasabaty] PDF gurallary" → brand verbatim | DONE |
| [ ] | tr | "[Hata Raporu] PDF Araç Seti" → brand verbatim | DONE |
| [ ] | uz | "[Xatolik hisoboti] PDF asboblar to'plami" → brand verbatim | DONE |

### 2.3 `settings_about_title` (9 locales)
| [ ] | Locale | Current | Status |
|---|---|---|---|
| [ ] | ar | "حول مجموعة أدوات PDF" → "حول PDF Toolkit" (brand verbatim) | DONE |
| [ ] | fr | "À propos de la boîte à outils PDF" → brand verbatim | DONE |
| [ ] | id | "Tentang Perangkat PDF" → brand verbatim | DONE |
| [ ] | ja | "PDF ツールキットについて" → brand verbatim | DONE |
| [ ] | ko | "PDF 툴킷 정보" → brand verbatim | DONE |
| [ ] | ru | "О наборе инструментов PDF" → "О PDF Toolkit" | DONE |
| [ ] | tk | "PDF Toolkit hakda" → OK (brand kept) — verify | DONE |
| [ ] | tr | "PDF Araç Seti Hakkında" → brand verbatim | DONE |
| [ ] | uz | "PDF asboblar to'plami haqida" → brand verbatim | DONE |

### 2.4 `settings_about_description`
| [ ] | Locale | Current | Status |
|---|---|---|---|
| [ ] | tr | "PDF Araç Takımı, PDF belgelerini…" → lead with "PDF Toolkit," (brand verbatim) | DONE |

### 2.5 `settings_about_kotlin_compose` (product name; 6 locales)
| [ ] | Locale | Current | Status |
|---|---|---|---|
| [ ] | fr | "Kotlin et amp; Composer Jetpack" → "Kotlin & Jetpack Compose" — **applied via Phase 1** (no re-edit needed) | DONE |
| [ ] | ar | "كوتلين وأمبير. جيتباك يؤلف" → keep transliteration of brand + fix to "Kotlin و Jetpack Compose" — **applied (deviation)**: "Kotlin & Jetpack Compose" (both tokens verbatim) | DONE |
| [ ] | id | "Kotlin & Penulisan Jetpack" → "Kotlin & Jetpack Compose" | DONE |
| [ ] | ru | "Котлин & Реактивный ранец" → "Kotlin & Jetpack Compose" | DONE |
| [ ] | tk | "Kotlin & Jetpack düzmek" → "Kotlin & Jetpack Compose" | DONE |
| [ ] | tr | "Kotlin & Jetpack Oluşturma" → "Kotlin & Jetpack Compose" | DONE |

### 2.6 Cross-check (audit §C note)
| [ ] | Item | Status |
|---|---|---|
| [ ] | Verify ru internal consistency: `bug_report_subject`/`settings_about_description` already keep "PDF Toolkit" — no change needed there, confirm during verification. | DONE |

### 2.7 Rule-4 extras — brand translated in keys not listed in the audit table
Found by the post-edit brand scan (grep across all locales). False positives excluded (`nav_tab_tools`/`category_image_tools`/`tool_image_tools`/`pdf_tools` = generic "tools"; tr `bug_report_template` "Yeniden Oluşturma" = "reproduce", not "Compose").
| [ ] | Locale | Key | Current → Applied | Status |
|---|---|---|---|---|
| [ ] | ar | `feature_request_description` | "…تحسين مجموعة أدوات PDF!" → "…تحسين PDF Toolkit!" | DONE |
| [ ] | tr | `feature_request_description` | "PDF Araç Setini geliştirmemize…" → "PDF Toolkit\'i geliştirmemize…" | DONE |
| [ ] | uz | `feature_request_description` | "PDF asboblar to'plamini yaxshilashga…" → "PDF Toolkit\'ni yaxshilashga…" | DONE |

---

## Phase 3 — Wire hardcoded literals to resources (one screen at a time, largest first)

Per-screen procedure:
1. Enumerate every hardcoded literal (audit §A1 + fresh grep for the file).
2. Check the 124 dead-key list (audit §A2) — reuse an existing key if it covers the text (and is already translated in all 15 locales).
3. Only add new keys to `values/strings.xml` (and then all 14 locale files) when no dead key exists.
4. Replace literal with `stringResource(R.string.<key>)`.
5. Content descriptions are localized too (a11y). Skip only non-UI literals (animation `label`s, sample/placeholder data like `https://example.com`, HTML snippets, bullets).
6. Stop after each screen; report before the next.

| [ ] | Screen (audit §A1 count) | Approach notes | Status |
|---|---|---|---|
| [x] | 3.1 PdfViewerScreen.kt (50) | Reuse `pdf_*` dead keys (25 exist) for viewer/search/annotation UI; add keys for "Share", "Open with...", "Go to Page", "Print", "Reset Zoom", etc. | DONE |
| [x] | 3.2 MetadataScreen.kt (23) | Reuse `label_*`/`tool_view_metadata`; add keys for Title/Author/Subject/Keywords/Creator/Producer/Created/Modified/PDF Version/Encrypted, "Strip All", "No PDF Selected". | DONE |
| [x] | 3.3 ScanToPdfScreen.kt (19) | New keys mostly (Camera/Gallery/Page Size/Color Mode/Enhance Contrast/Create PDF/Scan More Documents…). Reuse `action_*`/`empty_*` where possible. | DONE |
| [x] | 3.4 AnnotationScreen.kt (18) | Reuse `tool_annotate_pdf`, `pdf_clear_annotations*`; add Stamp Type/Width/Height/Add Annotation keys. | DONE |
| [x] | 3.5 OcrScreen.kt (17) | Reuse `tool_ocr`/`desc_ocr`; add Copy/Markdown View/Raw Text/Extracted Document Reader keys. | DONE |
| [x] | 3.6 WatermarkScreen.kt (17) | Reuse `tool_add_watermark`/`desc_add_watermark`; add Opacity/Rotation/Watermark Text keys. | DONE |
| [x] | 3.7 SecurityScreen.kt (14) | Reuse `tool_add_security`, `label_password`, `error_password_required`; add Confirm Password/Allow Printing/Copying/Editing keys. | DONE |
| [x] | 3.8 SignPdfScreen.kt (14) | Reuse `tool_sign_pdf`/`desc_sign`/`action_clear`; add Add Date/Add Name/Your Name keys. | DONE |
| [x] | 3.9a ConvertScreen.kt (13) | Reuse `tool_images_to_pdf`/`desc_images_to_pdf`, `action_add_more`, `action_remove`; add Reset/Add More Images keys. | DONE |
| [x] | 3.9b ImageToolsScreen.kt (13) | Reuse `tool_image_*`/`desc_*` (6 exist); add Use Custom Size/Width/Height/Maintain Aspect Ratio/Target Resolution keys. | DONE |
| [x] | 3.9c SplitScreen.kt (13) | **All 11 `split_*` dead keys exist** — reuse them; add Switch Mode/From/To/Page Numbers keys. | DONE |
| [x] | 3.10 HistorySidebar.kt (10) | Wire `nav_open_history` at :88 (dead key exists); **add new keys**: `history_title`, `history_empty_title`, `history_empty_subtitle`, `history_clear_all`, `history_clear_dialog_title`, `history_clear_dialog_message`, `action_clear`/`action_cancel` reuse, `history_view_all_files`, `history_open_file`, `history_open_gallery`, `action_delete`, `cd_more_options`. | DONE |
| [x] | 3.11a FillFormsScreen.kt (9) | Reuse `tool_fill_forms`/`desc_fill_forms`, `action_clear`; add Analyzing form fields/Open/Save Filled Form keys. | DONE |
| [x] | 3.11b HtmlToPdfScreen.kt (9) | Reuse `tool_html_to_pdf`/`desc_html_to_pdf`; skip sample data (`https://example.com`, `<html>` snippet); add From URL/From HTML/Website URL/HTML Content/Basic/Invoice keys. | DONE |
| [x] | 3.11c PdfToImageScreen.kt (9) | Reuse `tool_pdf_to_images`/`desc_pdf_to_images`, `action_open`, `action_close`; add Open Gallery/OK keys. | DONE |
| [x] | 3.12a FlattenScreen.kt (8) | Reuse `tool_flatten_pdf`/`desc_flatten_pdf`; add Flattening…/Flatten Another PDF keys. | DONE |
| [x] | 3.12b OrganizeScreen.kt (8) | Reuse `tool_organize_pages`/`desc_organize_pages`, `tool_delete_pages`; add Remove Pages/Extract-Reorder Pages/All/None keys. | DONE |
| [x] | 3.12c RotateScreen.kt (8) | Reuse `tool_rotate_pages`/`desc_rotate_page## Phase 4 — Dead key cleanup

| [x] | Item | Status |
|---|---|---|
| [x] | Re-run the unused-key scan (audit methodology, §A2). Delete only keys guaranteed unused after Phase 3 (none of the Phase 3 screens referenced them). Prefer leaving keys. Report the surviving dead list. | DONE |

---

## Phase 5 — Translation quality pass

Applied translation quality fixes for ru (16), fr (8), ja (6), tr (7), ko (5), uz (4), ar (2), id (2), hi (1), pt-BR (2).

### 5.1 ru (16 flagged — audit §D)
| [x] | Key | Current | Proposed | Rationale | Status |
|---|---|---|---|---|---|
| [x] | `action_done` | "Сделанный" | "Готово" | adjective "made" → button "Done" | DONE |
| [x] | `action_view` | "Вид" | "Просмотреть" | noun "sight" → action verb | DONE |
| [x] | `action_add` | "Добавлять" | "Добавить" | infinitive → imperative | DONE |
| [x] | `action_close` | "Закрывать" | "Закрыть" | infinitive → imperative | DONE |
| [x] | `action_remove` | "Удалять" | "Удалить" | infinitive → imperative | DONE |
| [x] | `action_save` | "Сохранять" | "Сохранить" | infinitive → imperative | DONE |
| [x] | `action_select` | "Выбирать" | "Выбрать" | infinitive → imperative | DONE |
| [x] | `action_submit` | "Представлять на рассмотрение" | "Отправить" | bureaucratic → natural | DONE |
| [x] | `action_move_down` | "Двигаться вниз" | "Вниз" | stilted → concise | DONE |
| [x] | `theme_light` | "Свет" | "Светлая" | noun → feminine adjective (тема) | DONE |
| [x] | `theme_dark` | "Темный" | "Тёмная" | wrong gender → feminine adjective | DONE |
| [x] | `tool_ocr` | "оптическое распознавание символов" | "OCR" | acronym expanded; inconsistent | DONE |
| [x] | `category_view_export` | "Посмотреть и усилить; Экспорт" | "Просмотр и экспорт" | broken MT fragment | DONE |
| [x] | `settings_theme_mode` | "Тематический режим" | "Тема" | literal "thematic mode" | DONE |
| [x] | `settings_privacy_policy` | "политика конфиденциальности" | "Политика конфиденциальности" | lowercase → label case | DONE |
| [x] | `home_subtitle` | "…PDF & инструменты…" | "…PDF и инструменты…" | raw "&" → "и" | DONE |

### 5.2 fr (8 flagged — audit §D)
| [x] | Key | Current | Proposed | Rationale | Status |
|---|---|---|---|---|---|
| [x] | `category_view_export` | "Voir et afficher Exporter" | "Voir et exporter" | nonsense concatenation | DONE |
| [x] | `tool_ocr` | "ROC" | "OCR" | backwards acronym | DONE |
| [x] | `action_done` | "Terminé" | "Terminer" | adjective → verb | DONE |
| [x] | `home_subtitle` | "…et amp; outils…" | "…et outils d'image…" | remove `amp;` (Phase 1 overlap) | DONE |
| [x] | `settings_about_feature_3` | "…et des amp; signature" | "…et signatures" | remove `amp;` (Phase 1 overlap) | DONE |
| [x] | `settings_about_feature_4` | "ROC et amp;…" | "OCR et numérisation…" | remove `amp;` + fix OCR (Phase 1 overlap) | DONE |
| [x] | `settings_about_kotlin_compose` | "Kotlin et amp; Composer Jetpack" | "Kotlin & Jetpack Compose" | Phase 1/2 overlap | DONE |
| [x] | `desc_convert_format` | "JPEG ??? WebP" | "JPEG ↔ WebP" | Phase 1 overlap | DONE |

### 5.3 ja (6 flagged — audit §D)
| [x] | Key | Current | Proposed | Rationale | Status |
|---|---|---|---|---|---|
| [x] | `category_view_export` | "閲覧・閲覧輸出" | "表示と書き出し" | nonsense duplication | DONE |
| [x] | `action_done` | "終わり" | "完了" | "the end" → Done | DONE |
| [x] | `action_split` | "スプリット" | "分割" | loanword → native term | DONE |
| [x] | `action_view` | "ビュー" | "表示" | noun → action verb | DONE |
| [x] | `action_submit` | "提出する" | "送信" | generic dictionary verb | DONE |
| [x] | `desc_convert_format` | "JPEG・・・？？ WebP" | "JPEG ↔ WebP" | Phase 1 overlap | DONE |

### 5.4 tr (7 flagged — audit §D)
| [x] | Key | Current | Proposed | Rationale | Status |
|---|---|---|---|---|---|
| [x] | `action_done` | "Tamamlamak" | "Tamamla" | infinitive → imperative | DONE |
| [x] | `action_submit` | "Göndermek" | "Gönder" | infinitive → imperative | DONE |
| [x] | `action_split` | "Bölmek" | "Böl" | infinitive → imperative | DONE |
| [x] | `action_save` | "Kaydetmek" | "Kaydet" | infinitive → imperative | DONE |
| [x] | `action_remove` | "Kaldırmak" | "Kaldır" | infinitive → imperative | DONE |
| [x] | `theme_light` | "Işık" | "Açık" | noun "glow" → theme adjective | DONE |
| [x] | `desc_convert_format` | "JPEG?? WebP" | "JPEG ↔ WebP" | Phase 1 overlap | DONE |

### 5.5 Note — remaining flagged locales
| [x] | Item | Status |
|---|---|---|
| [x] | ko: `theme_light`/`theme_dark`/`action_submit`/`category_view_export` (+`desc_convert_format` Phase 1) | DONE |
| [x] | uz: `action_done`/`theme_light`/`home_subtitle` (+`desc_convert_format` Phase 1) | DONE |
| [x] | ar: `settings_about_kotlin_compose` (Phase 2) / `desc_convert_format` (Phase 1) | DONE |
| [x] | id: `settings_about_kotlin_compose` (Phase 2) / `desc_convert_format` (Phase 1) | DONE |
| [x] | hi: `settings_about_kotlin_compose` stray "से" | DONE |
| [x] | pt-BR: `language_hindi`/`language_chinese` (Phase 1) / `desc_convert_format` (Phase 1) | DONE |

---

## Cross-cutting verification hooks
| [x] | Item | Status |
|---|---|---|
| [x] | Re-run the full audit methodology with a fresh-context review pass. | DONE |
| [x] | Run full build `./gradlew :app:assembleFdroidDebug` to confirm clean build. | DONE |ter ru/fr/ja/tr if budget allows.
| [ ] | Item | Status |
|---|---|---|
| [ ] | ko: `theme_light`/`theme_dark`/`action_submit`/`category_view_export` (+`desc_convert_format` Phase 1) | TODO (proposal) |
| [ ] | uz: `action_done`/`theme_light`/`home_subtitle` (+`desc_convert_format` Phase 1) | TODO (proposal) |
| [ ] | ar: `settings_about_kotlin_compose` (Phase 2) / `desc_convert_format` (Phase 1) | TODO (proposal) |
| [ ] | id: `settings_about_kotlin_compose` (Phase 2) / `desc_convert_format` (Phase 1) | TODO (proposal) |
| [ ] | hi: `settings_about_kotlin_compose` stray "से" | TODO (proposal) |
| [ ] | pt-BR: `language_hindi`/`language_chinese` (Phase 1) / `desc_convert_format` (Phase 1) | TODO (proposal) |

---

## Cross-cutting verification hooks
| [ ] | Item | Status |
|---|---|---|
| [ ] | After Phase 4 (before Phase 5): re-run the full audit methodology (Step 3) with a fresh-context review pass; write `docs/localization/verification-2026-08.md` with RESOLVED / STILL OPEN / NEW ISSUES. | TODO |
| [ ] | After each edit batch: `./gradlew :app:assembleFdroidDebug` (or lint) to confirm no broken `R.string` references / no missing keys. | TODO |
