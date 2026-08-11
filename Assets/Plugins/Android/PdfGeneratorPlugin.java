package com.pdfgenerationmodule;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class PdfGeneratorPlugin {

    private static final String TAG = "PdfGeneratorPlugin";

    // ── Header marker — must match C# ChecklistPdfDataProvider.HEADER_SECTION_MARKER ──
    private static final String HEADER_SECTION_MARKER = "__HEADER__";

    // ── Header row indices — mirrors BuildHeaderSection() row order in C# ────────────
    private static final int HEADER_ROW_LOGOS = 0; // Label=leftLogoB64, Value=rightLogoB64
    private static final int HEADER_ROW_PROJECT_TITLE = 1; // Label=projectTitle
    private static final int HEADER_ROW_PROJECT_NOS = 2; // Label=left field, Value=right field
    private static final int HEADER_ROW_CHECKLIST_TITLE = 3; // Label=checklistTitle
    private static final int HEADER_ROW_COMPANY_DOC = 4; // Label=left field, Value=right field
    private static final int HEADER_ROW_CONTRACTOR_DOC = 5; // Label=sheet number label

    // ── Table column proportions (body table, out of content width) ──────────────────
    private static final float COL_SNO_RATIO = 0.08f;
    private static final float COL_LABEL_RATIO = 0.54f;
    private static final float COL_COMMENT_RATIO = 0.22f;
    private static final float COL_VALUE_RATIO = 0.16f;

    // ── Header column proportions (out of content width) ─────────────────────────────
    private static final float HDR_LOGO_COL_RATIO = 0.15f; // left logo
    private static final float HDR_MID_COL_RATIO = 0.70f; // centre content
    // right logo = remainder (0.15f)

    // ── Header row heights (px) ───────────────────────────────────────────────────────
    private static final int HDR_ROW_TITLE_H = 80; // project title row
    private static final int HDR_ROW_PROJ_NOS_H = 58; // company/contractor project nos
    private static final int HDR_ROW_GENERIC_H = 40; // checklist title, doc no
    private static final int HDR_ROW_LOWER_H = 38; // description row inside header

    // ── Total header height (sum of all row heights) ──────────────────────────────────
    private static final int TOTAL_HEADER_H = HDR_ROW_TITLE_H
            + HDR_ROW_PROJ_NOS_H
            + HDR_ROW_GENERIC_H // checklist title
            + HDR_ROW_GENERIC_H // tag/unit row
            + HDR_ROW_LOWER_H; // description row

    // ── Gap between header and body content ───────────────────────────────────────────
    private static final int HEADER_BODY_GAP = 35;

    // ── Signature section layout ──────────────────────────────────────────────────────
    private static final int SIGNATURE_ROW_H = 54;
    private static final int SIGNATURE_BLOCK_GAP = 18;

    // ── Optional evidence image layout (generic, data-driven) ───────────────────────
    private static final int MAX_ROW_IMAGES = 5;
    private static final int EVIDENCE_MAX_COLUMNS = 2;
    private static final int EVIDENCE_MAX_DOUBLE_IMAGE_W_PX = 252;
    private static final int EVIDENCE_MAX_SINGLE_IMAGE_W_PX = 520;
    private static final int EVIDENCE_MAX_IMAGE_H_PX = 248;
    private static final int EVIDENCE_IMAGE_GAP_X_PX = 16;
    private static final int EVIDENCE_ROW_GAP_Y_PX = 10;
    private static final int EVIDENCE_BLOCK_TOP_GAP_PX = 6;
    private static final int EVIDENCE_BLOCK_BOTTOM_GAP_PX = 6;
    private static final int EVIDENCE_CAPTION_GAP_Y_PX = 6;
    private static final int EVIDENCE_CELL_PAD_X_PX = 8;
    private static final int EVIDENCE_CELL_PAD_Y_PX = 8;
    private static final String EVIDENCE_HEADING_TEXT = "Inspection Evidence";

    // ── Colours ───────────────────────────────────────────────────────────────────────
    private static final int COLOR_HEADER_BG = Color.parseColor("#1A3C5E");
    private static final int COLOR_HEADER_TEXT = Color.WHITE;
    private static final int COLOR_ROW_ALT = Color.parseColor("#F0F4F8");
    private static final int COLOR_ROW_NORMAL = Color.WHITE;
    private static final int COLOR_NOTE_TEXT = Color.parseColor("#555555");
    private static final int COLOR_BORDER = Color.parseColor("#CCCCCC");
    private static final int COLOR_HDR_BORDER = Color.BLACK;
    private static final int COLOR_CHECKED = Color.parseColor("#1A7A3C");
    private static final int COLOR_UNCHECKED = Color.parseColor("#C0392B");
    private static final int COLOR_TITLE = Color.parseColor("#1A3C5E");
    private static final int COLOR_SUBTITLE = Color.parseColor("#666666");
    private static final int COLOR_FOOTER = Color.parseColor("#999999");
    private static final int COLOR_DIVIDER = Color.parseColor("#1A3C5E");

    // ─────────────────────────────────────────────────────────────────────────────────
    // Public entry point (called from Unity via JNI — signature unchanged)
    // ─────────────────────────────────────────────────────────────────────────────────
    public static String generatePdf(
            Context context,
            String fileName,
            String jsonData,
            int pageWidth,
            int pageHeight,
            int margin,
            int titleFontSize,
            int sectionFontSize,
            int bodyFontSize,
            int rowSpacing,
            int sectionSpacing) {
        try {
            PdfDocument pdfDocument = buildPdfDocument(
                    jsonData, pageWidth, pageHeight, margin,
                    titleFontSize, sectionFontSize, bodyFontSize,
                    rowSpacing, sectionSpacing);

            if (pdfDocument == null) {
                Log.e(TAG, "buildPdfDocument returned null");
                return "";
            }

            String resultPath;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resultPath = saveViaMediaStore(context, pdfDocument, fileName);
            } else {
                resultPath = saveViaDirectPath(pdfDocument, fileName);
            }

            pdfDocument.close();
            return resultPath != null ? resultPath : "";
        } catch (Exception e) {
            Log.e(TAG, "generatePdf failed: " + e.getMessage(), e);
            return "";
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────
    // Save helpers
    // ─────────────────────────────────────────────────────────────────────────────────
    private static String saveViaMediaStore(
            Context context, PdfDocument pdf, String fileName) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/pdf");
            values.put(MediaStore.Downloads.IS_PENDING, 1);

            Uri collection = MediaStore.Downloads.getContentUri(
                    MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Uri fileUri = context.getContentResolver().insert(collection, values);

            if (fileUri == null) {
                Log.e(TAG, "MediaStore insert returned null URI");
                return null;
            }

            try (OutputStream os
                    = context.getContentResolver().openOutputStream(fileUri)) {
                pdf.writeTo(os);
            }

            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            context.getContentResolver().update(fileUri, values, null, null);

            return fileUri.toString();
        } catch (Exception e) {
            Log.e(TAG, "saveViaMediaStore failed: " + e.getMessage(), e);
            return null;
        }
    }

    private static String saveViaDirectPath(PdfDocument pdf, String fileName) {
        try {
            File dir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            File out = new File(dir, fileName);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                pdf.writeTo(fos);
            }
            return out.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "saveViaDirectPath failed: " + e.getMessage(), e);
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────
    // Core document builder
    //
    // Strategy for "header on every page + X/Y page numbers":
    //   Pass 1  — dry-run (no drawing) to count total pages.
    //   Pass 2  — real draw, injecting totalPages into every header.
    // ─────────────────────────────────────────────────────────────────────────────────
    private static PdfDocument buildPdfDocument(
            String jsonData,
            int pageWidth, int pageHeight, int margin,
            int titleFontSize, int sectionFontSize, int bodyFontSize,
            int rowSpacing, int sectionSpacing) {
        try {
            JSONObject doc = new JSONObject(jsonData);
            String footerText = doc.optString("FooterText", "");
            JSONArray sections = doc.optJSONArray("Sections");

            // ── Pass 1: count total pages ──────────────────────────────
            int totalPages = countTotalPages(
                    sections, pageWidth, pageHeight, margin,
                    sectionFontSize, bodyFontSize, rowSpacing, sectionSpacing);

            // ── Pass 2: render with known totalPages ───────────────────
            return renderDocument(
                    sections, footerText,
                    pageWidth, pageHeight, margin,
                    titleFontSize, sectionFontSize, bodyFontSize,
                    rowSpacing, sectionSpacing, totalPages);

        } catch (Exception e) {
            Log.e(TAG, "buildPdfDocument failed: " + e.getMessage(), e);
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────
    // Pass 1 — dry-run page counter (no canvas / PdfDocument needed)
    // ─────────────────────────────────────────────────────────────────────────────────
    private static int countTotalPages(
            JSONArray sections,
            int pageWidth, int pageHeight, int margin,
            int sectionFontSize, int bodyFontSize, int rowSpacing, int sectionSpacing) {
        try {
            if (sections == null) {
                return 1;
            }

            int displayBodyFontSize = Math.max(10, bodyFontSize - 1);

            // The first content y-position on every page is just below the report header.
            // Checklist table header is added lazily only on pages that render checklist content.
            int headerRowH = Math.max(displayBodyFontSize + 14, 28);
            int firstY = margin + TOTAL_HEADER_H + HEADER_BODY_GAP;

            // Height available for body content on each page
            int footerReserve = margin + rowSpacing * 2;
            int pageBodyBottom = pageHeight - footerReserve;

            int pageCount = 1;
            int y = firstY;

            // We need a throwaway Paint to measure text wrapping
            Paint measurePaint = makePaint(Color.BLACK, displayBodyFontSize, Typeface.NORMAL);
            int contentW = pageWidth - (margin * 2);
            int colLabelW = (int) (contentW * COL_LABEL_RATIO);
            int colCommentW = (int) (contentW * COL_COMMENT_RATIO);
            int maxChecklistContentHOnFreshPage = pageBodyBottom - (firstY + headerRowH);

            boolean tableHeaderDrawnOnPage = false;

            for (int s = 0; s < sections.length(); s++) {
                JSONObject section = sections.getJSONObject(s);
                String sectionTitle = section.optString("SectionTitle", "");

                // Skip the header marker section — it doesn't consume body space
                if (HEADER_SECTION_MARKER.equals(sectionTitle)) {
                    continue;
                }

                JSONArray rows = section.optJSONArray("Rows");

                // Section title line
                if (!sectionTitle.isEmpty()) {
                    y += sectionFontSize + sectionSpacing;
                }

                if (rows != null) {
                    for (int r = 0; r < rows.length(); r++) {
                        JSONObject row = rows.getJSONObject(r);
                        boolean isNote = row.optBoolean("IsNoteRow", false);
                        boolean isHeader = row.optBoolean("IsHeaderRow", false);
                        if (isNote) {
                            continue;
                        }

                        if (isHeader) {
                            continue;
                        }

                        if (!tableHeaderDrawnOnPage) {
                            if (y + headerRowH > pageBodyBottom) {
                                pageCount++;
                                y = firstY;
                            }
                            y += headerRowH;
                            tableHeaderDrawnOnPage = true;
                        }

                        String label = row.optString("Label", "");

                        String commentText = "";
                        if (!isHeader && r + 1 < rows.length()) {
                            JSONObject nextRow = rows.getJSONObject(r + 1);
                            if (nextRow.optBoolean("IsNoteRow", false)) {
                                commentText = extractCommentText(nextRow.optString("Label", ""));
                            }
                        }

                        int wrappedLines = Math.max(
                                measureWrappedLines(measurePaint, label, colLabelW - 12),
                                measureWrappedLines(measurePaint, commentText, colCommentW - 12)
                        );
                        int rowH = Math.max(displayBodyFontSize + 14, 10 + wrappedLines * (displayBodyFontSize + 4));
                        List<String> imagePaths = getRenderableImagePaths(row);

                        int firstEvidenceChunkH = 0;
                        if (!imagePaths.isEmpty()) {
                            int captionH = getEvidenceCaptionHeight(displayBodyFontSize);
                            int firstRowImages = getImagesInVisualRow(imagePaths.size(), 0);
                            int firstRowMaxH = getEvidenceRowMaxImageHeight(
                                    imagePaths,
                                    0,
                                    firstRowImages,
                                    contentW);
                            if (firstRowMaxH > 0) {
                                firstEvidenceChunkH = getEvidenceHeadingRowHeight(displayBodyFontSize)
                                        + getEvidenceImageRowHeight(firstRowMaxH, captionH)
                                        + EVIDENCE_ROW_GAP_Y_PX;
                            }
                        }

                        if (firstEvidenceChunkH > 0
                                && y + rowH + firstEvidenceChunkH > pageBodyBottom
                                && rowH + firstEvidenceChunkH <= maxChecklistContentHOnFreshPage) {
                            pageCount++;
                            y = firstY;
                            tableHeaderDrawnOnPage = false;
                            r--;
                            continue;
                        }

                        if (y + rowH > pageBodyBottom) {
                            if (rowH <= maxChecklistContentHOnFreshPage) {
                                // New page and retry this same row.
                                pageCount++;
                                y = firstY;
                                tableHeaderDrawnOnPage = false;
                                r--;
                                continue;
                            }
                        }
                        y += rowH;

                        if (!imagePaths.isEmpty()) {
                            int captionH = getEvidenceCaptionHeight(displayBodyFontSize);
                            int imageIndex = 0;
                            boolean drawHeading = true;

                            while (imageIndex < imagePaths.size()) {
                                if (!tableHeaderDrawnOnPage) {
                                    if (y + headerRowH > pageBodyBottom) {
                                        pageCount++;
                                        y = firstY;
                                    }
                                    y += headerRowH;
                                    tableHeaderDrawnOnPage = true;
                                }

                                if (drawHeading) {
                                    int headingRowH = getEvidenceHeadingRowHeight(displayBodyFontSize);
                                    if (y + headingRowH > pageBodyBottom) {
                                        pageCount++;
                                        y = firstY;
                                        tableHeaderDrawnOnPage = false;
                                        continue;
                                    }
                                    y += headingRowH;
                                    drawHeading = false;
                                }

                                int rowImages = getImagesInVisualRow(imagePaths.size(), imageIndex);
                                int rowMaxImageH = getEvidenceRowMaxImageHeight(
                                        imagePaths,
                                        imageIndex,
                                        rowImages,
                                        contentW);

                                if (rowMaxImageH <= 0) {
                                    imageIndex += rowImages;
                                    continue;
                                }

                                int rowNeededH = getEvidenceImageRowHeight(rowMaxImageH, captionH);
                                if (y + rowNeededH > pageBodyBottom) {
                                    pageCount++;
                                    y = firstY;
                                    tableHeaderDrawnOnPage = false;
                                    drawHeading = true;
                                    continue;
                                }

                                y += rowNeededH;
                                imageIndex += rowImages;
                                if (imageIndex < imagePaths.size()) {
                                    if (y + EVIDENCE_ROW_GAP_Y_PX > pageBodyBottom) {
                                        pageCount++;
                                        y = firstY;
                                        tableHeaderDrawnOnPage = false;
                                        drawHeading = true;
                                        continue;
                                    }
                                    y += EVIDENCE_ROW_GAP_Y_PX;
                                }
                            }
                        }
                    }
                }
                y += sectionSpacing;
            }

            int signatureBlockHeight = (SIGNATURE_ROW_H * 9) + SIGNATURE_BLOCK_GAP + 24;
            if (y + signatureBlockHeight > pageBodyBottom) {
                pageCount++;
            }

            return pageCount;
        } catch (Exception e) {
            Log.e(TAG, "countTotalPages failed: " + e.getMessage(), e);
            return 1;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────
    // Pass 2 — full render
    // ─────────────────────────────────────────────────────────────────────────────────
    private static PdfDocument renderDocument(
            JSONArray sections,
            String footerText,
            int pageWidth, int pageHeight, int margin,
            int titleFontSize, int sectionFontSize, int bodyFontSize,
            int rowSpacing, int sectionSpacing,
            int totalPages) {
        try {
            // ── Paints ────────────────────────────────────────────────
            int displayBodyFontSize = Math.max(10, bodyFontSize - 1);
            Paint footerPaint = makePaint(COLOR_FOOTER, displayBodyFontSize - 2, Typeface.ITALIC);
            Paint headerCellPaint = makePaint(COLOR_HEADER_TEXT, displayBodyFontSize, Typeface.BOLD);
            Paint bodyPaint = makePaint(Color.parseColor("#222222"), displayBodyFontSize, Typeface.NORMAL);
            Paint donePaint = makePaint(COLOR_CHECKED, displayBodyFontSize, Typeface.BOLD);
            Paint notDonePaint = makePaint(COLOR_UNCHECKED, displayBodyFontSize, Typeface.BOLD);

            Paint borderPaint = new Paint();
            borderPaint.setColor(COLOR_BORDER);
            borderPaint.setStrokeWidth(1f);
            borderPaint.setStyle(Paint.Style.STROKE);

            Paint dividerPaint = new Paint();
            dividerPaint.setColor(COLOR_DIVIDER);
            dividerPaint.setStrokeWidth(2f);

            // ── Page setup ────────────────────────────────────────────
            PdfDocument pdfDoc = new PdfDocument();
            PdfDocument.PageInfo pageInfo
                    = new PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create();
            PdfDocument.Page page = pdfDoc.startPage(pageInfo);
            Canvas canvas = page.getCanvas();

            int contentW = pageWidth - (margin * 2);
            int colSnoW = (int) (contentW * COL_SNO_RATIO);
            int colLabelW = (int) (contentW * COL_LABEL_RATIO);
            int colCommentW = (int) (contentW * COL_COMMENT_RATIO);
            int colValueW = contentW - colSnoW - colLabelW - colCommentW;
            int headerRowH = Math.max(displayBodyFontSize + 14, 28);
            int pageBodyBottom = pageHeight - (margin + rowSpacing * 2);

            // ── Locate and cache the header rows once ─────────────────
            JSONArray headerRows = null;
            if (sections != null) {
                for (int s = 0; s < sections.length(); s++) {
                    JSONObject sec = sections.getJSONObject(s);
                    if (HEADER_SECTION_MARKER.equals(sec.optString("SectionTitle", ""))) {
                        headerRows = sec.optJSONArray("Rows");
                        break;
                    }
                }
            }

            // ── Draw header on page 1 ─────────────────────────────────
            int currentPage = 1;
            int y = drawStructuredHeader(
                    canvas, headerRows, margin, margin,
                    pageWidth, bodyFontSize, totalPages, currentPage,
                    true);
            boolean tableHeaderDrawnOnPage = false;
            int firstY = margin + TOTAL_HEADER_H + HEADER_BODY_GAP;
            int maxChecklistContentHOnFreshPage = pageBodyBottom - (firstY + headerRowH);

            // ── Sections ──────────────────────────────────────────────
            if (sections != null) {
                for (int s = 0; s < sections.length(); s++) {
                    JSONObject section = sections.getJSONObject(s);
                    String sectionTitle = section.optString("SectionTitle", "");
                    JSONArray rows = section.optJSONArray("Rows");

                    // Skip header marker — already rendered above
                    if (HEADER_SECTION_MARKER.equals(sectionTitle)) {
                        continue;
                    }

                    // ── Normal section title ───────────────────────────
                    if (!sectionTitle.isEmpty()) {
                        Paint sPaint = makePaint(COLOR_TITLE, sectionFontSize, Typeface.BOLD);
                        canvas.drawText(sectionTitle, margin, y, sPaint);
                        y += sectionFontSize + sectionSpacing;
                    }

                    // ── Body rows ─────────────────────────────────────
                    if (rows != null) {
                        boolean altRow = false;
                        int serialNo = 1;

                        for (int r = 0; r < rows.length(); r++) {
                            JSONObject row = rows.getJSONObject(r);
                            String label = row.optString("Label", "");
                            boolean isHeader = row.optBoolean("IsHeaderRow", false);
                            boolean isNote = row.optBoolean("IsNoteRow", false);
                            boolean isChecked = row.optBoolean("IsChecked", false);

                            if (isNote) {
                                continue;
                            }

                            if (isHeader) {
                                altRow = false;
                                continue;
                            }

                            if (!tableHeaderDrawnOnPage) {
                                if (y + headerRowH > pageBodyBottom) {
                                    drawFooter(canvas, footerText, footerPaint,
                                            margin, pageWidth, pageHeight, rowSpacing, dividerPaint);
                                    pdfDoc.finishPage(page);

                                    currentPage++;
                                    PdfDocument.PageInfo np
                                            = new PdfDocument.PageInfo.Builder(
                                                    pageWidth, pageHeight,
                                                    currentPage).create();
                                    page = pdfDoc.startPage(np);
                                    canvas = page.getCanvas();
                                    y = drawStructuredHeader(
                                            canvas, headerRows, margin, margin,
                                            pageWidth, bodyFontSize, totalPages, currentPage,
                                            true);
                                }

                                y = drawChecklistTableHeader(
                                        canvas, margin, y, pageWidth, headerRowH,
                                        colSnoW, colLabelW, colCommentW, colValueW,
                                        headerCellPaint, borderPaint);
                                tableHeaderDrawnOnPage = true;
                            }

                            String commentText = "";
                            if (!isHeader && r + 1 < rows.length()) {
                                JSONObject nextRow = rows.getJSONObject(r + 1);
                                if (nextRow.optBoolean("IsNoteRow", false)) {
                                    commentText = extractCommentText(nextRow.optString("Label", ""));
                                }
                            }

                            int wrappedLines = Math.max(
                                    measureWrappedLines(bodyPaint, label, colLabelW - 12),
                                    measureWrappedLines(bodyPaint, commentText, colCommentW - 12)
                            );
                            int rowH = Math.max(displayBodyFontSize + 14, 10 + wrappedLines * (displayBodyFontSize + 4));
                            List<String> imagePaths = getRenderableImagePaths(row);
                            int itemRowColor = altRow ? COLOR_ROW_ALT : COLOR_ROW_NORMAL;

                            int firstEvidenceChunkH = 0;
                            if (!imagePaths.isEmpty()) {
                                int evidenceCaptionH = getEvidenceCaptionHeight(displayBodyFontSize);
                                int firstRowImages = getImagesInVisualRow(imagePaths.size(), 0);
                                int firstRowMaxH = getEvidenceRowMaxImageHeight(
                                        imagePaths,
                                        0,
                                        firstRowImages,
                                        contentW);
                                if (firstRowMaxH > 0) {
                                    firstEvidenceChunkH = getEvidenceHeadingRowHeight(displayBodyFontSize)
                                            + getEvidenceImageRowHeight(firstRowMaxH, evidenceCaptionH)
                                            + EVIDENCE_ROW_GAP_Y_PX;
                                }
                            }

                            if (firstEvidenceChunkH > 0
                                    && y + rowH + firstEvidenceChunkH > pageBodyBottom
                                    && rowH + firstEvidenceChunkH <= maxChecklistContentHOnFreshPage) {
                                drawFooter(canvas, footerText, footerPaint,
                                        margin, pageWidth, pageHeight, rowSpacing, dividerPaint);
                                pdfDoc.finishPage(page);

                                currentPage++;
                                PdfDocument.PageInfo np
                                        = new PdfDocument.PageInfo.Builder(
                                                pageWidth, pageHeight,
                                                currentPage).create();
                                page = pdfDoc.startPage(np);
                                canvas = page.getCanvas();

                                y = drawStructuredHeader(
                                        canvas, headerRows, margin, margin,
                                        pageWidth, bodyFontSize, totalPages, currentPage,
                                        true);
                                tableHeaderDrawnOnPage = false;
                                y = drawChecklistTableHeader(
                                        canvas, margin, y, pageWidth, headerRowH,
                                        colSnoW, colLabelW, colCommentW, colValueW,
                                        headerCellPaint, borderPaint);
                                tableHeaderDrawnOnPage = true;
                            }

                            // ── Page overflow — start new page ────────
                            if (y + rowH > pageBodyBottom) {
                                drawFooter(canvas, footerText, footerPaint,
                                        margin, pageWidth, pageHeight, rowSpacing, dividerPaint);
                                pdfDoc.finishPage(page);

                                currentPage++;
                                PdfDocument.PageInfo np
                                        = new PdfDocument.PageInfo.Builder(
                                                pageWidth, pageHeight,
                                                currentPage).create();
                                page = pdfDoc.startPage(np);
                                canvas = page.getCanvas();

                                // ── Draw header on every new page ─────
                                y = drawStructuredHeader(
                                        canvas, headerRows, margin, margin,
                                        pageWidth, bodyFontSize, totalPages, currentPage,
                                        true);
                                tableHeaderDrawnOnPage = false;
                                y = drawChecklistTableHeader(
                                        canvas, margin, y, pageWidth, headerRowH,
                                        colSnoW, colLabelW, colCommentW, colValueW,
                                        headerCellPaint, borderPaint);
                                tableHeaderDrawnOnPage = true;
                            }

                            Paint bgPaint = new Paint();
                            bgPaint.setColor(itemRowColor);
                            bgPaint.setStyle(Paint.Style.FILL);
                            int rowTop = y;
                            int rowBottom = rowTop + rowH;
                            canvas.drawRect(margin, rowTop,
                                    pageWidth - margin, rowBottom, bgPaint);

                            canvas.drawRect(margin, rowTop,
                                    pageWidth - margin, rowBottom, borderPaint);

                            drawTextCentredInCell(canvas, Integer.toString(serialNo),
                                    margin, rowTop, colSnoW, rowH, bodyPaint);
                            drawTextInColumn(canvas, label, margin + colSnoW + 6, rowTop,
                                    colLabelW - 12, rowH, bodyPaint);
                            drawTextInColumn(canvas, commentText, margin + colSnoW + colLabelW + 6, rowTop,
                                    colCommentW - 12, rowH, bodyPaint);

                            String value = isChecked ? "Y" : "N";
                            Paint valuePaint = isChecked ? donePaint : notDonePaint;
                            float vw = valuePaint.measureText(value);
                            canvas.drawText(value,
                                    margin + colSnoW + colLabelW + colCommentW + (colValueW - vw) / 2f,
                                    rowTop + (rowH / 2f) + (valuePaint.getTextSize() / 3f),
                                    valuePaint);

                            canvas.drawLine(
                                    margin + colSnoW, rowTop,
                                    margin + colSnoW, rowBottom, borderPaint);
                            canvas.drawLine(
                                    margin + colSnoW + colLabelW, rowTop,
                                    margin + colSnoW + colLabelW, rowBottom, borderPaint);
                            canvas.drawLine(
                                    margin + colSnoW + colLabelW + colCommentW, rowTop,
                                    margin + colSnoW + colLabelW + colCommentW, rowBottom, borderPaint);

                            serialNo++;

                            y += rowH;

                            if (!imagePaths.isEmpty()) {
                                Paint evidenceTitlePaint = makePaint(COLOR_TITLE, displayBodyFontSize, Typeface.BOLD);
                                Paint evidenceCaptionPaint = makePaint(Color.parseColor("#444444"), Math.max(10, displayBodyFontSize - 1), Typeface.NORMAL);

                                int evidenceCaptionH = getEvidenceCaptionHeight(displayBodyFontSize);
                                int imageIndex = 0;
                                boolean drawEvidenceHeading = true;

                                while (imageIndex < imagePaths.size()) {
                                    if (!tableHeaderDrawnOnPage) {
                                        if (y + headerRowH > pageBodyBottom) {
                                            drawFooter(canvas, footerText, footerPaint,
                                                    margin, pageWidth, pageHeight, rowSpacing, dividerPaint);
                                            pdfDoc.finishPage(page);

                                            currentPage++;
                                            PdfDocument.PageInfo np
                                                    = new PdfDocument.PageInfo.Builder(
                                                            pageWidth, pageHeight,
                                                            currentPage).create();
                                            page = pdfDoc.startPage(np);
                                            canvas = page.getCanvas();

                                            y = drawStructuredHeader(
                                                    canvas, headerRows, margin, margin,
                                                    pageWidth, bodyFontSize, totalPages, currentPage,
                                                    true);
                                        }

                                        y = drawChecklistTableHeader(
                                                canvas, margin, y, pageWidth, headerRowH,
                                                colSnoW, colLabelW, colCommentW, colValueW,
                                                headerCellPaint, borderPaint);
                                        tableHeaderDrawnOnPage = true;
                                    }

                                    if (drawEvidenceHeading) {
                                        int headingRowH = getEvidenceHeadingRowHeight(displayBodyFontSize);
                                        if (y + headingRowH > pageBodyBottom) {
                                            drawFooter(canvas, footerText, footerPaint,
                                                    margin, pageWidth, pageHeight, rowSpacing, dividerPaint);
                                            pdfDoc.finishPage(page);

                                            currentPage++;
                                            PdfDocument.PageInfo np
                                                    = new PdfDocument.PageInfo.Builder(
                                                            pageWidth, pageHeight,
                                                            currentPage).create();
                                            page = pdfDoc.startPage(np);
                                            canvas = page.getCanvas();

                                            y = drawStructuredHeader(
                                                    canvas, headerRows, margin, margin,
                                                    pageWidth, bodyFontSize, totalPages, currentPage,
                                                    true);
                                            tableHeaderDrawnOnPage = false;
                                            continue;
                                        }

                                        Paint headingBgPaint = new Paint();
                                        headingBgPaint.setColor(itemRowColor);
                                        headingBgPaint.setStyle(Paint.Style.FILL);
                                        canvas.drawRect(margin, y, pageWidth - margin, y + headingRowH, headingBgPaint);
                                        canvas.drawRect(margin, y, pageWidth - margin, y + headingRowH, borderPaint);

                                        drawTextCentredInCell(
                                                canvas,
                                                EVIDENCE_HEADING_TEXT,
                                                margin,
                                                y,
                                                contentW,
                                                headingRowH,
                                                evidenceTitlePaint);
                                        y += headingRowH;
                                        drawEvidenceHeading = false;
                                    }

                                    int imagesInVisualRow = getImagesInVisualRow(imagePaths.size(), imageIndex);
                                    int rowMaxImageH = getEvidenceRowMaxImageHeight(
                                            imagePaths,
                                            imageIndex,
                                            imagesInVisualRow,
                                            contentW);

                                    if (rowMaxImageH <= 0) {
                                        imageIndex += imagesInVisualRow;
                                        continue;
                                    }

                                    int evidenceRowH = getEvidenceImageRowHeight(rowMaxImageH, evidenceCaptionH);
                                    if (y + evidenceRowH > pageBodyBottom) {
                                        drawFooter(canvas, footerText, footerPaint,
                                                margin, pageWidth, pageHeight, rowSpacing, dividerPaint);
                                        pdfDoc.finishPage(page);

                                        currentPage++;
                                        PdfDocument.PageInfo np
                                                = new PdfDocument.PageInfo.Builder(
                                                        pageWidth, pageHeight,
                                                        currentPage).create();
                                        page = pdfDoc.startPage(np);
                                        canvas = page.getCanvas();

                                        y = drawStructuredHeader(
                                                canvas, headerRows, margin, margin,
                                                pageWidth, bodyFontSize, totalPages, currentPage,
                                                true);
                                        tableHeaderDrawnOnPage = false;
                                        drawEvidenceHeading = true;
                                        continue;
                                    }

                                    Paint evidenceBgPaint = new Paint();
                                    evidenceBgPaint.setColor(itemRowColor);
                                    evidenceBgPaint.setStyle(Paint.Style.FILL);
                                    canvas.drawRect(margin, y, pageWidth - margin, y + evidenceRowH, evidenceBgPaint);
                                    canvas.drawRect(margin, y, pageWidth - margin, y + evidenceRowH, borderPaint);

                                    int availableW = contentW - (EVIDENCE_CELL_PAD_X_PX * 2);
                                    int totalGapW = imagesInVisualRow > 1 ? EVIDENCE_IMAGE_GAP_X_PX : 0;
                                    int cellW = (availableW - totalGapW) / imagesInVisualRow;
                                    int drawMaxW = getEvidenceDrawMaxWidth(contentW, imagesInVisualRow);
                                    int rowStartX = margin + EVIDENCE_CELL_PAD_X_PX;
                                    int imageY = y + EVIDENCE_CELL_PAD_Y_PX;

                                    for (int col = 0; col < imagesInVisualRow; col++) {
                                        int imageGlobalIndex = imageIndex + col;
                                        String imagePath = imagePaths.get(imageGlobalIndex);
                                        int[] dims = getImageDimensions(imagePath);
                                        if (dims == null || dims[0] <= 0 || dims[1] <= 0) {
                                            continue;
                                        }

                                        int[] drawDims = scaledDims(
                                                dims[0],
                                                dims[1],
                                                drawMaxW,
                                                EVIDENCE_MAX_IMAGE_H_PX);

                                        int cellStartX = rowStartX + (col * (cellW + EVIDENCE_IMAGE_GAP_X_PX));
                                        int imageX = cellStartX + Math.max(0, (cellW - drawDims[0]) / 2);

                                        Bitmap bmp = BitmapFactory.decodeFile(imagePath);
                                        if (bmp != null) {
                                            try {
                                                canvas.drawBitmap(
                                                        bmp,
                                                        null,
                                                        new Rect(imageX, imageY, imageX + drawDims[0], imageY + drawDims[1]),
                                                        null);
                                            } finally {
                                                bmp.recycle();
                                            }
                                        }

                                        String caption = "Image " + (imageGlobalIndex + 1) + " of " + imagePaths.size();
                                        drawTextCentredInCell(
                                                canvas,
                                                caption,
                                                cellStartX,
                                                imageY + rowMaxImageH + EVIDENCE_CAPTION_GAP_Y_PX,
                                                cellW,
                                                evidenceCaptionH,
                                                evidenceCaptionPaint);
                                    }

                                    y += evidenceRowH;
                                    imageIndex += imagesInVisualRow;
                                    drawEvidenceHeading = false;
                                    if (imageIndex < imagePaths.size()) {
                                        if (y + EVIDENCE_ROW_GAP_Y_PX > pageBodyBottom) {
                                            drawFooter(canvas, footerText, footerPaint,
                                                    margin, pageWidth, pageHeight, rowSpacing, dividerPaint);
                                            pdfDoc.finishPage(page);

                                            currentPage++;
                                            PdfDocument.PageInfo np
                                                    = new PdfDocument.PageInfo.Builder(
                                                            pageWidth, pageHeight,
                                                            currentPage).create();
                                            page = pdfDoc.startPage(np);
                                            canvas = page.getCanvas();

                                            y = drawStructuredHeader(
                                                    canvas, headerRows, margin, margin,
                                                    pageWidth, bodyFontSize, totalPages, currentPage,
                                                    true);
                                            tableHeaderDrawnOnPage = false;
                                            drawEvidenceHeading = true;
                                        } else {
                                            y += EVIDENCE_ROW_GAP_Y_PX;
                                        }
                                    }
                                }

                            }

                            altRow = !altRow;
                        }
                    }
                    y += sectionSpacing;
                }
            }

            int signatureBlockHeight = (SIGNATURE_ROW_H * 9) + SIGNATURE_BLOCK_GAP + 24;
            if (y + signatureBlockHeight > pageHeight - (margin + rowSpacing * 2)) {
                drawFooter(canvas, footerText, footerPaint,
                        margin, pageWidth, pageHeight, rowSpacing, dividerPaint);
                pdfDoc.finishPage(page);

                currentPage++;
                PdfDocument.PageInfo np
                        = new PdfDocument.PageInfo.Builder(pageWidth, pageHeight, currentPage).create();
                page = pdfDoc.startPage(np);
                canvas = page.getCanvas();
                y = drawStructuredHeader(canvas, headerRows, margin, margin,
                        pageWidth, bodyFontSize, totalPages, currentPage,
                        true);
            }

            y = drawSignatureSection(canvas, margin, y, pageWidth, displayBodyFontSize,
                    totalPages, currentPage, borderPaint, bodyPaint);

            drawFooter(canvas, footerText, footerPaint,
                    margin, pageWidth, pageHeight, rowSpacing, dividerPaint);
            pdfDoc.finishPage(page);
            return pdfDoc;
        } catch (Exception e) {
            Log.e(TAG, "renderDocument failed: " + e.getMessage(), e);
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────
    // Structured header renderer
    //
    // Reads 6 generic rows sent by C# ChecklistPdfDataProvider.BuildHeaderSection().
    // Zero project-specific knowledge here — all strings come from the JSON payload.
    //
    // Layout:
    //
    //  ┌──────────┬────────────────────────────────────────────┬──────────┐
    //  │          │        PROJECT TITLE (bold, centred)        │          │
    //  │  Left    ├────────────────────────┬───────────────────┤  Right   │
    //  │  Logo    │  COMPANY PROJECT NO.   │ CONTRACTOR PROJ.  │  Logo    │
    //  │          │       10477            │      D7650         │          │
    //  │          ├────────────────────────┴───────────────────┤          │
    //  │          │        CHECKLIST TITLE (centred)           │          │
    //  │          ├──────────────────────────────────┬─────────┤          │
    //  │          │  COMPANY DOC. NO.: AD-xxx         │ Rev. X  │          │
    //  │          ├──────────────────────────────────┼─────────┤          │
    //  │          │  CONTRACTOR DOC. NO.: AD-xxx      │ 1/20    │          │
    //  └──────────┴──────────────────────────────────┴─────────┴──────────┘
    //
    // Returns the y-coordinate where body content should begin.
    // ─────────────────────────────────────────────────────────────────────────────────
    private static int drawStructuredHeader(
            Canvas canvas,
            JSONArray rows,
            int margin,
            int startY,
            int pageWidth,
            int bodyFontSize,
            int totalChecklistPages,
            int checklistPage,
            boolean showSheetNumber) {
        try {
            if (rows == null || rows.length() < 6) {
                Log.w(TAG, "drawStructuredHeader: expected 6 rows, got "
                        + (rows == null ? "null" : rows.length()));
                // Return a safe starting Y even if header rows are missing
                return startY + TOTAL_HEADER_H + HEADER_BODY_GAP;
            }

            int contentW = pageWidth - (margin * 2);
            int logoColW = (int) (contentW * HDR_LOGO_COL_RATIO); // left logo cell
            int midColW = (int) (contentW * HDR_MID_COL_RATIO);  // centre content
            int rightColW = contentW - logoColW - midColW;           // right logo cell

            int x = margin;
            int y = startY;

            // ── Paints for header ──────────────────────────────────────
            Paint hdrBorderPaint = new Paint();
            hdrBorderPaint.setColor(COLOR_HDR_BORDER);
            hdrBorderPaint.setStrokeWidth(1.5f);
            hdrBorderPaint.setStyle(Paint.Style.STROKE);

            Paint titlePaint = makePaint(Color.BLACK, bodyFontSize + 4, Typeface.BOLD);
            titlePaint.setTextAlign(Paint.Align.CENTER);

            Paint boldPaint = makePaint(Color.BLACK, bodyFontSize - 1, Typeface.BOLD);
            boldPaint.setTextAlign(Paint.Align.CENTER);

            Paint normalPaint = makePaint(Color.BLACK, bodyFontSize - 1, Typeface.NORMAL);
            normalPaint.setTextAlign(Paint.Align.CENTER);

            // Slightly smaller text only for the date/done-by split row to prevent truncation.
            Paint row2BoldPaint = makePaint(Color.BLACK, bodyFontSize - 1, Typeface.BOLD);
            row2BoldPaint.setTextAlign(Paint.Align.CENTER);
            Paint row2DateValuePaint = makePaint(Color.BLACK, Math.max(10, bodyFontSize - 2), Typeface.NORMAL);
            row2DateValuePaint.setTextAlign(Paint.Align.CENTER);
            Paint row2DoneByValuePaint = makePaint(Color.BLACK, bodyFontSize - 1, Typeface.NORMAL);
            row2DoneByValuePaint.setTextAlign(Paint.Align.CENTER);

            // ── Outer border ───────────────────────────────────────────
            canvas.drawRect(x, y, x + contentW, y + TOTAL_HEADER_H, hdrBorderPaint);

            // ── Vertical column lines (full height) ───────────────────
            int logoEndX = x + logoColW;
            int midEndX = logoEndX + midColW;
            canvas.drawLine(logoEndX, y, logoEndX, y + TOTAL_HEADER_H, hdrBorderPaint);
            canvas.drawLine(midEndX, y, midEndX, y + TOTAL_HEADER_H, hdrBorderPaint);

            // ── Row 0: logos (left and right cells) ───────────────────
            JSONObject row0 = rows.getJSONObject(HEADER_ROW_LOGOS);
            String leftLogoB64 = row0.optString("Label", "");
            String rightLogoB64 = row0.optString("Value", "");

            drawLogoInCell(canvas, leftLogoB64, x, y, logoColW, TOTAL_HEADER_H);

            // Reserve the lower band of the right panel for Sheet Number.
            int rightSheetBandH = HDR_ROW_GENERIC_H + HDR_ROW_LOWER_H;
            int rightLogoAreaH = TOTAL_HEADER_H - rightSheetBandH;
            int rightSheetTop = y + rightLogoAreaH;
            canvas.drawLine(midEndX, rightSheetTop, midEndX + rightColW, rightSheetTop, hdrBorderPaint);

            // Reduce padding so right logos stay readable in print while preserving aspect ratio.
            drawLogoInCell(canvas, rightLogoB64, midEndX, y, rightColW, rightLogoAreaH, 2, 2);

            // ── Row 1: project title ───────────────────────────────────
            JSONObject row1 = rows.getJSONObject(HEADER_ROW_PROJECT_TITLE);
            String projectTitle = row1.optString("Label", "");
            int titleRowTop = y;
            int titleRowBot = y + HDR_ROW_TITLE_H;
            canvas.drawLine(logoEndX, titleRowBot, midEndX, titleRowBot, hdrBorderPaint);
            drawTextWrappedCentredInCell(canvas, projectTitle,
                    logoEndX, titleRowTop, midColW, HDR_ROW_TITLE_H, titlePaint);
            y = titleRowBot;

            // ── Row 2: date and inspector (split into two halves) ─────
            JSONObject row2 = rows.getJSONObject(HEADER_ROW_PROJECT_NOS);
            String dateLine = row2.optString("Label", "");
            String doneByLine = row2.optString("Value", "");
            int leftDateCellW = (int) (midColW * 0.58f);
            int rightDoneByCellW = midColW - leftDateCellW;
            int projRowBot = y + HDR_ROW_PROJ_NOS_H;

            canvas.drawLine(logoEndX, projRowBot, midEndX, projRowBot, hdrBorderPaint);
            // vertical split inside mid column
            canvas.drawLine(logoEndX + leftDateCellW, y, logoEndX + leftDateCellW, projRowBot, hdrBorderPaint);

            drawKeyValueInCell(canvas, dateLine,
                    logoEndX, y, leftDateCellW, HDR_ROW_PROJ_NOS_H,
                    row2BoldPaint, row2DateValuePaint, true, false, false, 0.90f);
            drawKeyValueOnNextLineInCell(canvas, doneByLine,
                    logoEndX + leftDateCellW, y, rightDoneByCellW, HDR_ROW_PROJ_NOS_H,
                    row2BoldPaint, row2DoneByValuePaint, true);
            y = projRowBot;

            // ── Row 3: checklist title (full mid width, centred) ──────
            JSONObject row3 = rows.getJSONObject(HEADER_ROW_CHECKLIST_TITLE);
            String deviceLine = row3.optString("Label", "");
            String descriptionLine = row3.optString("Value", "");
            int chkRowBot = y + HDR_ROW_GENERIC_H;
            canvas.drawLine(logoEndX, chkRowBot, midEndX, chkRowBot, hdrBorderPaint);
            drawKeyValueInCell(canvas, deviceLine,
                    logoEndX, y, midColW, HDR_ROW_GENERIC_H,
                    boldPaint, normalPaint, true);
            y = chkRowBot;

            // ── Row 4: tag number (left) | unit/facility (right) ─
            JSONObject row4 = rows.getJSONObject(HEADER_ROW_COMPANY_DOC);
            String[] docParts = splitCell(row4.optString("Label", ""));
            String unitFacilityLine = row4.optString("Value", "");
            int docCellW = (int) (midColW * 0.66f);
            int revCellW = midColW - docCellW;
            int lowerBlockMidY = y + HDR_ROW_GENERIC_H;
            int lowerBlockBot = y + HDR_ROW_GENERIC_H + HDR_ROW_LOWER_H;

            // Keep Description inside the same left content box as the tag number.
            // Unit / Facility remains the right cell spanning the full lower block height.
            canvas.drawLine(logoEndX, lowerBlockBot, midEndX, lowerBlockBot, hdrBorderPaint);
            canvas.drawLine(logoEndX + docCellW, y, logoEndX + docCellW, lowerBlockBot, hdrBorderPaint);

            String tagLine = (docParts[0] + ": " + (docParts.length > 1 ? docParts[1] : ""));
            drawKeyValueInCell(canvas, tagLine,
                    logoEndX, y, docCellW, HDR_ROW_GENERIC_H,
                    boldPaint, normalPaint, true);
            drawKeyValueInCell(canvas, unitFacilityLine,
                    logoEndX + docCellW, y, revCellW, HDR_ROW_GENERIC_H + HDR_ROW_LOWER_H,
                    boldPaint, normalPaint, false);
            y = lowerBlockMidY - 2;

            // ── Row 5: description (full middle width, inside header) ─
            JSONObject row5 = rows.getJSONObject(HEADER_ROW_CONTRACTOR_DOC);
            String[] ctorParts = splitCell(row5.optString("Label", ""));
            int ctorRowBot = y + HDR_ROW_LOWER_H;

            // Keep sheet number inside right logo panel only.
            if (showSheetNumber) {
                String sheetLabel = (ctorParts[0] == null || ctorParts[0].trim().isEmpty())
                        ? "Sheet Number"
                        : ctorParts[0].trim();
                String pageLabel = checklistPage + "/" + totalChecklistPages;
                int sheetLabelH = rightSheetBandH / 2;
                int sheetValueH = rightSheetBandH - sheetLabelH;

                drawTextCentredInCell(
                        canvas,
                        sheetLabel,
                        midEndX,
                        rightSheetTop,
                        rightColW,
                        sheetLabelH,
                        boldPaint);
                drawTextCentredInCell(
                        canvas,
                        pageLabel,
                        midEndX,
                        rightSheetTop + sheetLabelH,
                        rightColW,
                        sheetValueH,
                        normalPaint);
            }

            // Draw Description in the existing centre band aligned with the right sheet band.
            drawKeyValueInCell(canvas, descriptionLine,
                    logoEndX, y, docCellW, HDR_ROW_LOWER_H,
                    boldPaint, normalPaint, true, true, false, 0.90f);

            y = ctorRowBot;

            return y + HEADER_BODY_GAP; // gap between header and body table
        } catch (Exception e) {
            Log.e(TAG, "drawStructuredHeader failed: " + e.getMessage(), e);
            return startY + TOTAL_HEADER_H + HEADER_BODY_GAP;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────
    // Generic drawing helpers
    // ─────────────────────────────────────────────────────────────────────────────────
    /**
     * Draw a base64 PNG centred inside a cell, scaled to fit.
     */
    private static void drawLogoInCell(
            Canvas canvas, String base64,
            int cellX, int cellY, int cellW, int cellH) {
        drawLogoInCell(canvas, base64, cellX, cellY, cellW, cellH, 10, 10);
    }

    private static void drawLogoInCell(
            Canvas canvas, String base64,
            int cellX, int cellY, int cellW, int cellH,
            int padX, int padY) {
        if (base64 == null || base64.isEmpty()) {
            return;
        }
        try {
            byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
            Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bmp == null) {
                return;
            }

            int[] dims = scaledDims(bmp.getWidth(), bmp.getHeight(),
                    Math.max(1, cellW - (padX * 2)),
                    Math.max(1, cellH - (padY * 2)));
            int lx = cellX + (cellW - dims[0]) / 2;
            int ly = cellY + (cellH - dims[1]) / 2;
            canvas.drawBitmap(bmp, null,
                    new Rect(lx, ly, lx + dims[0], ly + dims[1]), null);
        } catch (Exception e) {
            Log.w(TAG, "drawLogoInCell failed: " + e.getMessage());
        }
    }

    /**
     * Draw text horizontally and vertically centred inside a cell.
     */
    private static void drawTextCentredInCell(
            Canvas canvas, String text,
            int cellX, int cellY, int cellW, int cellH, Paint paint) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Paint.Align savedAlign = paint.getTextAlign();
        paint.setTextAlign(Paint.Align.CENTER);
        float tx = cellX + cellW / 2f;
        float ty = cellY + (cellH / 2f) + (paint.getTextSize() / 3f);
        canvas.drawText(text, tx, ty, paint);
        paint.setTextAlign(savedAlign);
    }

    /**
     * Draw "Key: Value" with bold key and normal value inside a single cell.
     */
    private static void drawKeyValueInCell(
            Canvas canvas,
            String text,
            int cellX,
            int cellY,
            int cellW,
            int cellH,
            Paint keyPaint,
            Paint valuePaint,
            boolean leftAlign) {
        drawKeyValueInCell(canvas, text, cellX, cellY, cellW, cellH,
                keyPaint, valuePaint, leftAlign, false, true, 0.70f);
    }

    private static void drawKeyValueInCell(
            Canvas canvas,
            String text,
            int cellX,
            int cellY,
            int cellW,
            int cellH,
            Paint keyPaint,
            Paint valuePaint,
            boolean leftAlign,
            boolean allowValueWrap,
            boolean shrinkKeyIfNeeded,
            float minValueSizeRatio) {
        if (text == null || text.isEmpty()) {
            return;
        }

        Paint keyLocal = new Paint(keyPaint);
        Paint valueLocal = new Paint(valuePaint);
        keyLocal.setTextAlign(Paint.Align.LEFT);
        valueLocal.setTextAlign(Paint.Align.LEFT);

        String[] kv = splitKeyValue(text);
        String key = kv[0];
        String value = kv[1];
        String spacer = value.isEmpty() ? "" : " ";

        float availableW = Math.max(0f, cellW - 16f);
        float minKeySize = keyLocal.getTextSize() * 0.70f;
        float keyW = keyLocal.measureText(key);
        while (shrinkKeyIfNeeded && keyW > availableW * 0.66f && keyLocal.getTextSize() > minKeySize) {
            keyLocal.setTextSize(keyLocal.getTextSize() * 0.95f);
            keyW = keyLocal.measureText(key);
        }

        float spacerW = value.isEmpty() ? 0f : valueLocal.measureText(spacer);
        float maxValueW = Math.max(0f, cellW - 16f - keyW - spacerW);
        float minValueSize = valueLocal.getTextSize() * minValueSizeRatio;
        while (!value.isEmpty()
                && !allowValueWrap
                && valueLocal.measureText(value) > maxValueW
                && valueLocal.getTextSize() > minValueSize) {
            valueLocal.setTextSize(valueLocal.getTextSize() * 0.96f);
        }

        while (!value.isEmpty()
                && !allowValueWrap
                && valueLocal.measureText(value) > maxValueW
                && value.length() > 1) {
            value = value.substring(0, value.length() - 1);
        }

        float valueW = valueLocal.measureText(value);
        float totalW = keyW + spacerW + valueW;
        float startX = leftAlign
                ? (cellX + 8f)
                : (cellX + Math.max(8f, (cellW - totalW) / 2f));
        if (allowValueWrap && !value.isEmpty()) {
            float lineGap = Math.max(2f, valueLocal.getTextSize() * 0.25f);
            float keyBaseline = cellY + Math.max(keyLocal.getTextSize() + 5f, (cellH * 0.42f));
            canvas.drawText(key, startX, keyBaseline, keyLocal);

            float valueStartX = startX + keyW + spacerW;
            float valueAvailFirstLine = Math.max(0f, cellX + cellW - 8f - valueStartX);
            float valueAvailFullLine = Math.max(0f, cellW - 16f);
            String firstLine = value;
            String secondLine = "";

            if (valueLocal.measureText(firstLine) > valueAvailFirstLine) {
                int breakIdx = findWrapIndex(value, valueLocal, valueAvailFirstLine);
                if (breakIdx > 0 && breakIdx < value.length()) {
                    firstLine = value.substring(0, breakIdx).trim();
                    secondLine = value.substring(breakIdx).trim();
                } else {
                    firstLine = "";
                    secondLine = value.trim();
                }
            }

            while (!firstLine.isEmpty()
                    && valueLocal.measureText(firstLine) > valueAvailFirstLine
                    && valueLocal.getTextSize() > minValueSize) {
                valueLocal.setTextSize(valueLocal.getTextSize() * 0.97f);
            }

            while (!secondLine.isEmpty()
                    && valueLocal.measureText(secondLine) > valueAvailFullLine
                    && valueLocal.getTextSize() > minValueSize) {
                valueLocal.setTextSize(valueLocal.getTextSize() * 0.97f);
            }

            if (valueLocal.measureText(firstLine) > valueAvailFirstLine) {
                firstLine = trimToWidth(firstLine, valueLocal, valueAvailFirstLine);
            }
            if (!secondLine.isEmpty() && valueLocal.measureText(secondLine) > valueAvailFullLine) {
                secondLine = trimToWidth(secondLine, valueLocal, valueAvailFullLine);
            }

            if (!firstLine.isEmpty()) {
                canvas.drawText(firstLine, valueStartX, keyBaseline, valueLocal);
            }
            if (!secondLine.isEmpty()) {
                float secondBaseline = Math.min(cellY + cellH - 6f,
                        keyBaseline + valueLocal.getTextSize() + lineGap);
                canvas.drawText(secondLine, startX, secondBaseline, valueLocal);
            }
            return;
        }

        float drawTextSize = Math.max(keyLocal.getTextSize(), valueLocal.getTextSize());
        float ty = cellY + (cellH / 2f) + (drawTextSize / 3f);

        canvas.drawText(key, startX, ty, keyLocal);
        if (!value.isEmpty()) {
            canvas.drawText(value, startX + keyW + spacerW, ty, valueLocal);
        }
    }

    private static void drawKeyValueOnNextLineInCell(
            Canvas canvas,
            String text,
            int cellX,
            int cellY,
            int cellW,
            int cellH,
            Paint keyPaint,
            Paint valuePaint,
            boolean centerAlign) {
        if (text == null || text.isEmpty()) {
            return;
        }

        Paint keyLocal = new Paint(keyPaint);
        Paint valueLocal = new Paint(valuePaint);
        keyLocal.setTextAlign(Paint.Align.LEFT);
        valueLocal.setTextAlign(Paint.Align.LEFT);

        String[] kv = splitKeyValue(text);
        String key = kv[0];
        String value = kv[1];
        float startX = cellX + 8f;
        float availableW = Math.max(0f, cellW - 16f);
        float keyStartX = centerAlign
                ? (cellX + Math.max(8f, (cellW - keyLocal.measureText(key)) / 2f))
                : startX;

        if (value.isEmpty()) {
            float singleBaseline = cellY + (cellH / 2f) + (keyLocal.getTextSize() / 3f);
            canvas.drawText(key, keyStartX, singleBaseline, keyLocal);
            return;
        }

        String firstLine = value;
        String secondLine = "";

        if (valueLocal.measureText(firstLine) > availableW) {
            int breakIdx = findWrapIndex(value, valueLocal, availableW);
            if (breakIdx > 0 && breakIdx < value.length()) {
                firstLine = value.substring(0, breakIdx).trim();
                secondLine = value.substring(breakIdx).trim();
            }
        }

        if (valueLocal.measureText(firstLine) > availableW) {
            firstLine = trimToWidth(firstLine, valueLocal, availableW);
        }
        if (!secondLine.isEmpty() && valueLocal.measureText(secondLine) > availableW) {
            secondLine = trimToWidth(secondLine, valueLocal, availableW);
        }

        int valueLineCount = secondLine.isEmpty() ? 1 : 2;
        float valueLineStep = valueLocal.getTextSize() + 2f;
        float blockHeight = keyLocal.getTextSize() + Math.max(6f, valueLineStep * valueLineCount);
        float blockTop = cellY + Math.max(4f, (cellH - blockHeight) / 2f);
        float keyBaseline = blockTop + keyLocal.getTextSize();
        float secondLineBaseline = keyBaseline + Math.max(6f, valueLineStep);

        canvas.drawText(key, keyStartX, keyBaseline, keyLocal);

        float firstLineX = centerAlign
                ? (cellX + Math.max(8f, (cellW - valueLocal.measureText(firstLine)) / 2f))
                : startX;
        canvas.drawText(firstLine, firstLineX, secondLineBaseline, valueLocal);
        if (!secondLine.isEmpty()) {
            float thirdLineBaseline = Math.min(cellY + cellH - 6f,
                    secondLineBaseline + valueLocal.getTextSize() + 2f);
            float secondLineX = centerAlign
                    ? (cellX + Math.max(8f, (cellW - valueLocal.measureText(secondLine)) / 2f))
                    : startX;
            canvas.drawText(secondLine, secondLineX, thirdLineBaseline, valueLocal);
        }
    }

    private static int findWrapIndex(String text, Paint paint, float maxWidth) {
        if (text == null || text.isEmpty()) {
            return -1;
        }

        int lastSpace = -1;
        for (int i = 1; i <= text.length(); i++) {
            String candidate = text.substring(0, i);
            if (paint.measureText(candidate) > maxWidth) {
                return lastSpace > 0 ? lastSpace : Math.max(1, i - 1);
            }
            if (Character.isWhitespace(text.charAt(i - 1))) {
                lastSpace = i - 1;
            }
        }
        return -1;
    }

    private static String trimToWidth(String text, Paint paint, float maxWidth) {
        String result = text == null ? "" : text;
        while (!result.isEmpty() && paint.measureText(result) > maxWidth) {
            result = result.substring(0, result.length() - 1).trim();
        }
        return result;
    }

    /**
     * Draw text word-wrapped and block-centred (horizontally and vertically)
     * inside a cell. Used for the project title row to prevent overflow.
     */
    private static void drawTextWrappedCentredInCell(
            Canvas canvas, String text,
            int cellX, int cellY, int cellW, int cellH, Paint paint) {
        if (text == null || text.isEmpty()) {
            return;
        }

        Paint.Align savedAlign = paint.getTextAlign();
        paint.setTextAlign(Paint.Align.LEFT);

        int padding = 8;
        int availW = cellW - (padding * 2);
        float lineH = paint.getTextSize() + 5f;

        // Build wrapped lines
        java.util.List<String> lines = new java.util.ArrayList<>();
        String[] words = text.split(" ");
        StringBuilder currentLine = new StringBuilder();

        for (String word : words) {
            String test = currentLine.length() == 0 ? word : currentLine + " " + word;
            if (paint.measureText(test) <= availW) {
                currentLine = new StringBuilder(test);
            } else {
                if (currentLine.length() > 0) {
                    lines.add(currentLine.toString());
                }
                currentLine = new StringBuilder(word);
            }
        }
        if (currentLine.length() > 0) {
            lines.add(currentLine.toString());
        }

        // Vertically centre the whole text block inside the cell
        float totalTextH = lines.size() * lineH;
        float drawY = cellY + (cellH - totalTextH) / 2f + paint.getTextSize();

        // Draw each line horizontally centred
        for (String line : lines) {
            float lineW = paint.measureText(line);
            float drawX = cellX + (cellW - lineW) / 2f;
            canvas.drawText(line, drawX, drawY, paint);
            drawY += lineH;
        }

        paint.setTextAlign(savedAlign);
    }

    /**
     * Draw text left-aligned, vertically centred inside a cell.
     */
    private static void drawTextLeftInCell(
            Canvas canvas, String text,
            float textX, int cellY, int maxW, int cellH, Paint paint) {
        if (text == null || text.isEmpty()) {
            return;
        }
        float ty = cellY + (cellH / 2f) + (paint.getTextSize() / 3f);
        // Simple truncation if text is wider than cell
        while (paint.measureText(text) > maxW && text.length() > 1) {
            text = text.substring(0, text.length() - 1);
        }
        canvas.drawText(text, textX, ty, paint);
    }

    /**
     * Split a "LABEL||VALUE" cell string into a two-element array. If the
     * delimiter is absent the whole string becomes element 0.
     */
    private static String[] splitCell(String input) {
        if (input == null) {
            return new String[]{"", ""};
        }

        String[] parts = input.split("\\|\\|", 2);
        if (parts.length < 2) {
            return new String[]{input.trim(), ""};
        }
        return new String[]{parts[0].trim(), parts[1].trim()};
    }

    private static String[] splitKeyValue(String input) {
        if (input == null) {
            return new String[]{"", ""};
        }

        int idx = input.indexOf(':');
        if (idx < 0) {
            return new String[]{input.trim(), ""};
        }

        String key = input.substring(0, idx + 1).trim();
        String value = input.substring(idx + 1).trim();
        return new String[]{key, value};
    }

    // ─────────────────────────────────────────────────────────────────────────────────
    // Unchanged submodule helpers
    // ─────────────────────────────────────────────────────────────────────────────────
    private static void drawFooter(
            Canvas canvas, String text, Paint paint,
            int margin, int pageWidth, int pageHeight,
            int rowSpacing, Paint dividerPaint) {
        if (text == null || text.isEmpty()) {
            return;
        }
        int fy = pageHeight - margin;
        canvas.drawLine(margin, fy - rowSpacing,
                pageWidth - margin, fy - rowSpacing, dividerPaint);
        canvas.drawText(text, margin, fy, paint);
    }

    private static int drawTextInColumn(
            Canvas canvas, String text, float x, int cellY,
            int maxWidth, int cellH, Paint paint) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        java.util.List<String> lines = new java.util.ArrayList<>();
        String[] words = text.split(" ");
        StringBuilder currentLine = new StringBuilder();

        for (String word : words) {
            String test = currentLine.length() == 0 ? word : currentLine + " " + word;
            if (paint.measureText(test) <= maxWidth) {
                currentLine = new StringBuilder(test);
            } else {
                if (currentLine.length() > 0) {
                    lines.add(currentLine.toString());
                }
                currentLine = new StringBuilder(word);
            }
        }
        if (currentLine.length() > 0) {
            lines.add(currentLine.toString());
        }

        float lineH = paint.getTextSize() + 4;
        float totalTextH = lines.size() * lineH;
        float drawY = cellY + (cellH - totalTextH) / 2f + paint.getTextSize();

        for (String line : lines) {
            canvas.drawText(line, x, drawY, paint);
            drawY += lineH;
        }

        return Math.max(0, lines.size() - 1);
    }

    private static List<String> getRenderableImagePaths(JSONObject row) {
        List<String> paths = new ArrayList<>();
        if (row == null) {
            return paths;
        }

        JSONArray arr = row.optJSONArray("ImagePaths");
        if (arr == null) {
            return paths;
        }

        int max = Math.min(arr.length(), MAX_ROW_IMAGES);
        for (int i = 0; i < max; i++) {
            String rawPath = arr.optString(i, "");
            if (rawPath == null) {
                continue;
            }

            String path = rawPath.trim();
            if (path.isEmpty()) {
                continue;
            }

            File file = new File(path);
            if (!file.exists()) {
                continue;
            }

            int[] dims = getImageDimensions(path);
            if (dims == null || dims[0] <= 0 || dims[1] <= 0) {
                continue;
            }

            paths.add(path);
        }

        return paths;
    }

    private static int[] getImageDimensions(String path) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, opts);
            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                return null;
            }
            return new int[]{opts.outWidth, opts.outHeight};
        } catch (Exception e) {
            Log.w(TAG, "getImageDimensions failed for path: " + path + " - " + e.getMessage());
            return null;
        }
    }

    private static boolean hasChecklistRows(JSONArray sections) {
        if (sections == null) {
            return false;
        }

        try {
            for (int s = 0; s < sections.length(); s++) {
                JSONObject section = sections.getJSONObject(s);
                String sectionTitle = section.optString("SectionTitle", "");
                if (HEADER_SECTION_MARKER.equals(sectionTitle)) {
                    continue;
                }

                JSONArray rows = section.optJSONArray("Rows");
                if (rows == null) {
                    continue;
                }

                for (int r = 0; r < rows.length(); r++) {
                    JSONObject row = rows.getJSONObject(r);
                    if (!row.optBoolean("IsHeaderRow", false)
                            && !row.optBoolean("IsNoteRow", false)) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "hasChecklistRows scan failed: " + e.getMessage());
        }

        return false;
    }

    private static int getEvidenceHeadingRowHeight(int displayBodyFontSize) {
        return Math.max(displayBodyFontSize + 12, 22);
    }

    private static int getEvidenceCaptionHeight(int displayBodyFontSize) {
        return Math.max(displayBodyFontSize + 2, 12);
    }

    private static int getImagesInVisualRow(int totalImages, int startIndex) {
        int remaining = totalImages - startIndex;
        if (remaining <= 0) {
            return 0;
        }
        if (remaining == 1) {
            return 1;
        }
        return Math.min(EVIDENCE_MAX_COLUMNS, remaining);
    }

    private static int getEvidenceDrawMaxWidth(int contentW, int imageCount) {
        int totalGapW = imageCount > 1 ? (imageCount - 1) * EVIDENCE_IMAGE_GAP_X_PX : 0;
        int availableW = contentW - (EVIDENCE_CELL_PAD_X_PX * 2);
        int cellW = (availableW - totalGapW) / imageCount;
        int cellInnerW = Math.max(1, cellW - (EVIDENCE_CELL_PAD_X_PX * 2));
        if (imageCount == 1) {
            return Math.min(EVIDENCE_MAX_SINGLE_IMAGE_W_PX, cellInnerW);
        }
        return Math.min(EVIDENCE_MAX_DOUBLE_IMAGE_W_PX, cellInnerW);
    }

    private static int getEvidenceImageRowHeight(int rowMaxImageH, int captionH) {
        return (EVIDENCE_CELL_PAD_Y_PX * 2)
                + rowMaxImageH
                + EVIDENCE_CAPTION_GAP_Y_PX
                + captionH;
    }

    private static int getEvidenceRowMaxImageHeight(
            List<String> imagePaths,
            int startIndex,
            int imageCount,
            int contentW) {
        if (imagePaths == null || imagePaths.isEmpty() || imageCount <= 0) {
            return 0;
        }

        int drawMaxW = getEvidenceDrawMaxWidth(contentW, imageCount);

        int maxH = 0;
        for (int i = 0; i < imageCount; i++) {
            int idx = startIndex + i;
            if (idx < 0 || idx >= imagePaths.size()) {
                continue;
            }

            int[] dims = getImageDimensions(imagePaths.get(idx));
            if (dims == null || dims[0] <= 0 || dims[1] <= 0) {
                continue;
            }

            int[] scaled = scaledDims(dims[0], dims[1], drawMaxW, EVIDENCE_MAX_IMAGE_H_PX);
            maxH = Math.max(maxH, scaled[1]);
        }

        return maxH;
    }

    private static int drawChecklistTableHeader(
            Canvas canvas,
            int margin,
            int rowTop,
            int pageWidth,
            int rowH,
            int colSnoW,
            int colLabelW,
            int colCommentW,
            int colValueW,
            Paint headerCellPaint,
            Paint borderPaint) {
        Paint bgPaint = new Paint();
        bgPaint.setColor(COLOR_HEADER_BG);
        bgPaint.setStyle(Paint.Style.FILL);
        int rowBottom = rowTop + rowH;

        canvas.drawRect(margin, rowTop, pageWidth - margin, rowBottom, bgPaint);
        canvas.drawRect(margin, rowTop, pageWidth - margin, rowBottom, borderPaint);

        drawTextCentredInCell(canvas, "Sl No",
                margin, rowTop, colSnoW, rowH, headerCellPaint);
        drawTextCentredInCell(canvas, "Checklist Item",
                margin + colSnoW, rowTop, colLabelW, rowH, headerCellPaint);
        drawTextCentredInCell(canvas, "Comments",
                margin + colSnoW + colLabelW, rowTop, colCommentW, rowH, headerCellPaint);
        drawTextCentredInCell(canvas, "Done (Y/N)",
                margin + colSnoW + colLabelW + colCommentW, rowTop, colValueW, rowH, headerCellPaint);

        canvas.drawLine(margin + colSnoW, rowTop, margin + colSnoW, rowBottom, borderPaint);
        canvas.drawLine(margin + colSnoW + colLabelW, rowTop,
                margin + colSnoW + colLabelW, rowBottom, borderPaint);
        canvas.drawLine(margin + colSnoW + colLabelW + colCommentW, rowTop,
                margin + colSnoW + colLabelW + colCommentW, rowBottom, borderPaint);

        return rowBottom;
    }

    private static int drawSignatureSection(
            Canvas canvas,
            int margin,
            int startY,
            int pageWidth,
            int bodyFontSize,
            int totalPages,
            int currentPage,
            Paint borderPaint,
            Paint bodyPaint) {
        int contentW = pageWidth - (margin * 2);
        int x = margin;
        int y = startY + SIGNATURE_BLOCK_GAP;
        int sectionRowH = SIGNATURE_ROW_H;
        int sectionH = sectionRowH * 3;
        int roleRowH = sectionRowH;
        int nameRowH = (sectionH - roleRowH) / 3;
        int dateRowH = (sectionH - roleRowH) / 3;
        int signRowH = sectionH - roleRowH - nameRowH - dateRowH;
        int sectionCount = 3;
        int totalH = sectionCount * sectionH;

        int detailStartX = x + 12;
        int dateLabelW = 44;
        int dateLineW = (int) (contentW * 0.56f);

        int signLineW = (int) (contentW * 0.56f);
        int signLabelW = 38;

        int nameTextX = x + 12;
        int namePrefixW = 66;
        int nameLineStartX = nameTextX + namePrefixW;
        int nameLineW = (int) (contentW * 0.68f);

        Paint headerPaint = makePaint(Color.BLACK, bodyFontSize, Typeface.BOLD);
        Paint cellPaint = makePaint(Color.BLACK, bodyFontSize, Typeface.NORMAL);
        Paint linePaint = new Paint(borderPaint);
        linePaint.setStrokeWidth(1f);
        linePaint.setStyle(Paint.Style.STROKE);

        Paint.Align savedAlignHeader = headerPaint.getTextAlign();
        Paint.Align savedAlignCell = cellPaint.getTextAlign();
        headerPaint.setTextAlign(Paint.Align.LEFT);
        cellPaint.setTextAlign(Paint.Align.LEFT);

        canvas.drawRect(x, y, x + contentW, y + totalH, borderPaint);

        String[] labels = new String[]{"Inspected by", "Verified by", "Approved by"};
        for (String label : labels) {
            int roleTop = y;
            int roleBottom = roleTop + roleRowH;
            int nameTop = roleBottom;
            int nameBottom = nameTop + nameRowH;
            int dateTop = nameBottom;
            int dateBottom = dateTop + dateRowH;
            int signTop = dateBottom;
            int signBottom = signTop + signRowH;

            float roleTextY = roleTop + (roleRowH / 2f) + (headerPaint.getTextSize() / 3f);
            canvas.drawText(label + ":", nameTextX, roleTextY, headerPaint);

            float nameTextY = nameTop + (nameRowH / 2f) + (headerPaint.getTextSize() / 3f);
            canvas.drawText("Name:", nameTextX, nameTextY, cellPaint);
            float nameLineY = nameTextY + 3f;
            canvas.drawLine(nameLineStartX, nameLineY, nameLineStartX + nameLineW, nameLineY, linePaint);

            float dateTextY = dateTop + (dateRowH / 2f) + (cellPaint.getTextSize() / 3f);

            canvas.drawText("Date:", detailStartX, dateTextY, cellPaint);
            float dateLineY = dateTextY + 3f;
            int dateLineStartX = detailStartX + dateLabelW;
            canvas.drawLine(dateLineStartX, dateLineY, dateLineStartX + dateLineW, dateLineY, linePaint);

            float signTextY = signTop + (signRowH / 2f) + (cellPaint.getTextSize() / 3f);
            canvas.drawText("Sign:", detailStartX, signTextY, cellPaint);
            float signLineY = signTextY + 3f;
            int signLineStartX = detailStartX + signLabelW;
            canvas.drawLine(signLineStartX, signLineY, signLineStartX + signLineW, signLineY, linePaint);

            y = signBottom;
            if (!label.equals(labels[labels.length - 1])) {
                // Divider between sections.
                canvas.drawLine(x, y, x + contentW, y, borderPaint);
            }
        }

        headerPaint.setTextAlign(savedAlignHeader);
        cellPaint.setTextAlign(savedAlignCell);

        return y;
    }

    private static int measureWrappedLines(Paint paint, String text, int maxWidth) {
        if (text == null || text.isEmpty() || maxWidth <= 0) {
            return 1;
        }

        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        int lines = 1;

        for (String word : words) {
            String test = line.length() == 0 ? word : line + " " + word;
            if (paint.measureText(test) <= maxWidth) {
                line = new StringBuilder(test);
            } else {
                lines++;
                line = new StringBuilder(word);
            }
        }

        return lines;
    }

    private static String extractCommentText(String noteLabel) {
        if (noteLabel == null) {
            return "";
        }
        String trimmed = noteLabel.trim();
        if (trimmed.startsWith("Note: ")) {
            return trimmed.substring("Note: ".length()).trim();
        }
        if (trimmed.startsWith("↳ Note: ")) {
            return trimmed.substring("↳ Note: ".length()).trim();
        }
        return trimmed;
    }

    private static int[] scaledDims(int srcW, int srcH, int maxW, int maxH) {
        float ratio = Math.min((float) maxW / srcW, (float) maxH / srcH);
        return new int[]{(int) (srcW * ratio), (int) (srcH * ratio)};
    }

    private static Paint makePaint(int color, int size, int style) {
        Paint p = new Paint();
        p.setColor(color);
        p.setTextSize(size);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, style));
        p.setAntiAlias(true);
        return p;
    }
}
