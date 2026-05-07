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

public class PdfGeneratorPlugin {

    private static final String TAG = "PdfGeneratorPlugin";

    // ── Header marker — must match C# ChecklistPdfDataProvider.HEADER_SECTION_MARKER ──
    private static final String HEADER_SECTION_MARKER = "__HEADER__";

    // ── Header row indices — mirrors BuildHeaderSection() row order in C# ────────────
    private static final int HEADER_ROW_LOGOS = 0; // Label=leftLogoB64, Value=rightLogoB64
    private static final int HEADER_ROW_PROJECT_TITLE = 1; // Label=projectTitle
    private static final int HEADER_ROW_PROJECT_NOS = 2; // Label="COMPANY PROJECT NO.||val", Value="CONTRACTOR PROJECT NO.||val"
    private static final int HEADER_ROW_CHECKLIST_TITLE = 3; // Label=checklistTitle
    private static final int HEADER_ROW_COMPANY_DOC = 4; // Label="COMPANY DOC. NO.||val", Value=revision
    private static final int HEADER_ROW_CONTRACTOR_DOC = 5; // Label="CONTRACTOR DOC. NO.||val"

    // ── Table column proportions (body table, out of content width) ──────────────────
    private static final float COL_LABEL_RATIO = 0.72f;
    private static final float COL_VALUE_RATIO = 0.28f;

    // ── Header column proportions (out of content width) ─────────────────────────────
    private static final float HDR_LOGO_COL_RATIO = 0.15f; // left logo
    private static final float HDR_MID_COL_RATIO = 0.70f; // centre content
    // right logo = remainder (0.15f)

    // ── Header row heights (px) ───────────────────────────────────────────────────────
    private static final int HDR_ROW_TITLE_H = 80; // project title row
    private static final int HDR_ROW_PROJ_NOS_H = 50; // company/contractor project nos
    private static final int HDR_ROW_GENERIC_H = 35; // checklist title, doc no, contractor doc

    // ── Total header height (sum of all row heights) ──────────────────────────────────
    private static final int TOTAL_HEADER_H = HDR_ROW_TITLE_H
            + HDR_ROW_PROJ_NOS_H
            + HDR_ROW_GENERIC_H // checklist title
            + HDR_ROW_GENERIC_H // company doc
            + HDR_ROW_GENERIC_H; // contractor doc

    // ── Gap between header and body content ───────────────────────────────────────────
    private static final int HEADER_BODY_GAP = 35;

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
                    bodyFontSize, rowSpacing, sectionSpacing);

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
            int bodyFontSize, int rowSpacing, int sectionSpacing) {
        try {
            if (sections == null) {
                return 1;
            }

            // The first content y-position on every page is just below the header
            int firstY = margin + TOTAL_HEADER_H + HEADER_BODY_GAP + bodyFontSize;

            // Height available for body content on each page
            int footerReserve = margin + rowSpacing * 2;
            int pageBodyBottom = pageHeight - footerReserve;

            int pageCount = 1;
            int y = firstY;

            // We need a throwaway Paint to measure text wrapping
            Paint measurePaint = makePaint(Color.BLACK, bodyFontSize, Typeface.NORMAL);
            int contentW = pageWidth - (margin * 2);
            int colLabelW = (int) (contentW * COL_LABEL_RATIO);

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
                    y += bodyFontSize + sectionSpacing; // approximate; matches render pass
                }

                if (rows != null) {
                    for (int r = 0; r < rows.length(); r++) {
                        JSONObject row = rows.getJSONObject(r);
                        boolean isNote = row.optBoolean("IsNoteRow", false);
                        if (isNote) {
                            continue;
                        }

                        String label = row.optString("Label", "");
                        boolean isHeader = row.optBoolean("IsHeaderRow", false);

                        int colLabelWForCalc = colLabelW - 8;
                        float labelWidth = measurePaint.measureText(label);
                        int wrappedLines = labelWidth <= colLabelWForCalc ? 0
                                : (int) Math.ceil(labelWidth / colLabelWForCalc);

                        // Peek at next row for inline note
                        boolean nextRowIsNote = false;
                        if (!isHeader && r + 1 < rows.length()) {
                            nextRowIsNote = rows.getJSONObject(r + 1)
                                    .optBoolean("IsNoteRow", false);
                        }
                        int noteLineH = nextRowIsNote ? (bodyFontSize + 6) : 0;
                        int rowH = rowSpacing + 6
                                + (wrappedLines * (bodyFontSize + 4))
                                + noteLineH;

                        if (y + rowSpacing > pageBodyBottom) {
                            // New page
                            pageCount++;
                            y = firstY;
                        }
                        y += rowH;
                    }
                }
                y += sectionSpacing;
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
            Paint footerPaint = makePaint(COLOR_FOOTER, bodyFontSize - 2, Typeface.ITALIC);
            Paint headerCellPaint = makePaint(COLOR_HEADER_TEXT, bodyFontSize, Typeface.BOLD);
            Paint bodyPaint = makePaint(Color.parseColor("#222222"), bodyFontSize, Typeface.NORMAL);
            Paint checkedPaint = makePaint(COLOR_CHECKED, bodyFontSize, Typeface.BOLD);
            Paint uncheckedPaint = makePaint(COLOR_UNCHECKED, bodyFontSize, Typeface.BOLD);

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
            int colLabelW = (int) (contentW * COL_LABEL_RATIO);
            int colValueW = contentW - colLabelW;

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
                    pageWidth, bodyFontSize, totalPages, currentPage);

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

                        for (int r = 0; r < rows.length(); r++) {
                            // ── Page overflow — start new page ────────
                            if (y + rowSpacing > pageHeight - (margin + rowSpacing * 2)) {
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
                                        pageWidth, bodyFontSize, totalPages, currentPage);
                            }

                            JSONObject row = rows.getJSONObject(r);
                            String label = row.optString("Label", "");
                            String value = row.optString("Value", "");
                            boolean isHeader = row.optBoolean("IsHeaderRow", false);
                            boolean isNote = row.optBoolean("IsNoteRow", false);
                            boolean isChecked = row.optBoolean("IsChecked", false);

                            int colLabelWForCalc = colLabelW - 8;
                            float labelWidth = bodyPaint.measureText(label);
                            int wrappedLines = labelWidth <= colLabelWForCalc ? 0
                                    : (int) Math.ceil(labelWidth / colLabelWForCalc);

                            // Peek ahead for inline note
                            boolean nextRowIsNote = false;
                            String noteText = "";
                            if (!isHeader && !isNote && r + 1 < rows.length()) {
                                JSONObject nextRow = rows.getJSONObject(r + 1);
                                nextRowIsNote = nextRow.optBoolean("IsNoteRow", false);
                                if (nextRowIsNote) {
                                    noteText = nextRow.optString("Label", "");
                                }
                            }
                            int noteLineH = nextRowIsNote ? (int) (bodyFontSize + 6) : 0;
                            int rowH = isNote ? 0
                                    : rowSpacing + 6
                                    + (wrappedLines * (int) (bodyFontSize + 4))
                                    + noteLineH;

                            if (isHeader) {
                                // Navy header row
                                Paint bgPaint = new Paint();
                                bgPaint.setColor(COLOR_HEADER_BG);
                                bgPaint.setStyle(Paint.Style.FILL);
                                canvas.drawRect(margin, y - bodyFontSize,
                                        pageWidth - margin, y - bodyFontSize + rowH, bgPaint);

                                canvas.drawText(label, margin + 6, y, headerCellPaint);
                                canvas.drawText(value, margin + colLabelW + 6, y, headerCellPaint);
                                canvas.drawLine(
                                        margin + colLabelW, y - bodyFontSize,
                                        margin + colLabelW, y - bodyFontSize + rowH, borderPaint);

                                altRow = false;
                            } else if (isNote) {
                                continue;
                            } else {
                                // Alternating data row
                                Paint bgPaint = new Paint();
                                bgPaint.setColor(altRow ? COLOR_ROW_ALT : COLOR_ROW_NORMAL);
                                bgPaint.setStyle(Paint.Style.FILL);
                                canvas.drawRect(margin, y - bodyFontSize,
                                        pageWidth - margin, y - bodyFontSize + rowH, bgPaint);

                                canvas.drawRect(margin, y - bodyFontSize,
                                        pageWidth - margin, y - bodyFontSize + rowH, borderPaint);

                                drawTextInColumn(canvas, label, margin + 6, y,
                                        colLabelW - 8, bodyPaint);

                                Paint valuePaint = isChecked ? checkedPaint : uncheckedPaint;
                                float vw = valuePaint.measureText(value);
                                canvas.drawText(value,
                                        margin + colLabelW + (colValueW - vw) / 2f, y, valuePaint);

                                canvas.drawLine(
                                        margin + colLabelW, y - bodyFontSize,
                                        margin + colLabelW, y - bodyFontSize + rowH, borderPaint);

                                if (nextRowIsNote && !noteText.isEmpty()) {
                                    String displayNote = noteText.contains("Note: ")
                                            ? noteText.substring(noteText.indexOf("Note: "))
                                            : noteText;
                                    Paint inlineNotePaint = makePaint(
                                            Color.parseColor("#1A3C5E"), bodyFontSize - 1, Typeface.ITALIC);
                                    canvas.drawText(displayNote, margin + 8,
                                            y + (rowSpacing / 2f) + 2, inlineNotePaint);
                                }

                                altRow = !altRow;
                            }

                            y += rowH;
                        }
                    }
                    y += sectionSpacing;
                }
            }

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
            int totalPages,
            int currentPage) {
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

            Paint leftPaint = makePaint(Color.BLACK, bodyFontSize - 1, Typeface.BOLD);

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
            drawLogoInCell(canvas, rightLogoB64, midEndX, y, rightColW, TOTAL_HEADER_H);

            // ── Row 1: project title ───────────────────────────────────
            JSONObject row1 = rows.getJSONObject(HEADER_ROW_PROJECT_TITLE);
            String projectTitle = row1.optString("Label", "");
            int titleRowTop = y;
            int titleRowBot = y + HDR_ROW_TITLE_H;
            canvas.drawLine(logoEndX, titleRowBot, midEndX, titleRowBot, hdrBorderPaint);
            drawTextWrappedCentredInCell(canvas, projectTitle,
                    logoEndX, titleRowTop, midColW, HDR_ROW_TITLE_H, titlePaint);
            y = titleRowBot;

            // ── Row 2: project numbers (split into two halves) ────────
            JSONObject row2 = rows.getJSONObject(HEADER_ROW_PROJECT_NOS);
            String[] companyParts = splitCell(row2.optString("Label", ""));
            String[] contractorParts = splitCell(row2.optString("Value", ""));
            int halfMid = midColW / 2;
            int projRowBot = y + HDR_ROW_PROJ_NOS_H;

            canvas.drawLine(logoEndX, projRowBot, midEndX, projRowBot, hdrBorderPaint);
            // vertical split inside mid column
            canvas.drawLine(logoEndX + halfMid, y, logoEndX + halfMid, projRowBot, hdrBorderPaint);

            // company project no — label top half, value bottom half
            int halfH = HDR_ROW_PROJ_NOS_H / 2;
            drawTextCentredInCell(canvas, companyParts[0],
                    logoEndX, y, halfMid, halfH, boldPaint);
            drawTextCentredInCell(canvas, companyParts.length > 1 ? companyParts[1] : "",
                    logoEndX, y + halfH, halfMid, halfH, normalPaint);

            // contractor project no
            drawTextCentredInCell(canvas, contractorParts[0],
                    logoEndX + halfMid, y, halfMid, halfH, boldPaint);
            drawTextCentredInCell(canvas, contractorParts.length > 1 ? contractorParts[1] : "",
                    logoEndX + halfMid, y + halfH, halfMid, halfH, normalPaint);
            y = projRowBot;

            // ── Row 3: checklist title (full mid width, centred) ──────
            JSONObject row3 = rows.getJSONObject(HEADER_ROW_CHECKLIST_TITLE);
            String checklistTitle = row3.optString("Label", "");
            int chkRowBot = y + HDR_ROW_GENERIC_H;
            canvas.drawLine(logoEndX, chkRowBot, midEndX, chkRowBot, hdrBorderPaint);
            drawTextCentredInCell(canvas, checklistTitle,
                    logoEndX, y, midColW, HDR_ROW_GENERIC_H, boldPaint);
            y = chkRowBot;

            // ── Row 4: company doc no (left ~75%) | revision (right ~25%) ─
            JSONObject row4 = rows.getJSONObject(HEADER_ROW_COMPANY_DOC);
            String[] docParts = splitCell(row4.optString("Label", ""));
            String revision = row4.optString("Value", "");
            int docCellW = (int) (midColW * 0.75f);
            int revCellW = midColW - docCellW;
            int docRowBot = y + HDR_ROW_GENERIC_H;

            canvas.drawLine(logoEndX, docRowBot, midEndX, docRowBot, hdrBorderPaint);
            // vertical split doc | revision
            canvas.drawLine(logoEndX + docCellW, y, logoEndX + docCellW, docRowBot, hdrBorderPaint);

            // "COMPANY DOC. NO.:  AD-xxx" drawn left-aligned
            String companyDocLine = (docParts[0] + ":  " + (docParts.length > 1 ? docParts[1] : ""));
            drawTextLeftInCell(canvas, companyDocLine, logoEndX + 6, y,
                    docCellW - 8, HDR_ROW_GENERIC_H, leftPaint);
            drawTextCentredInCell(canvas, revision,
                    logoEndX + docCellW, y, revCellW, HDR_ROW_GENERIC_H, boldPaint);
            y = docRowBot;

            // ── Row 5: contractor doc no (left ~75%) | page X/Y (right ~25%) ─
            JSONObject row5 = rows.getJSONObject(HEADER_ROW_CONTRACTOR_DOC);
            String[] ctorParts = splitCell(row5.optString("Label", ""));
            int ctorRowBot = y + HDR_ROW_GENERIC_H;

            // Note: no bottom border drawn here — outer border covers it
            canvas.drawLine(logoEndX + docCellW, y, logoEndX + docCellW, ctorRowBot, hdrBorderPaint);

            String ctorDocLine = (ctorParts[0] + ":  " + (ctorParts.length > 1 ? ctorParts[1] : ""));
            drawTextLeftInCell(canvas, ctorDocLine, logoEndX + 6, y,
                    docCellW - 8, HDR_ROW_GENERIC_H, leftPaint);

            // ── Page number — "currentPage / totalPages" format ───────
            String pageLabel = currentPage + "/" + totalPages;
            drawTextCentredInCell(canvas, pageLabel,
                    logoEndX + docCellW, y, revCellW, HDR_ROW_GENERIC_H, boldPaint);

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
                    cellW - 10, cellH - 10);
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
            Canvas canvas, String text, float x, float y,
            int maxWidth, Paint paint) {
        if (paint.measureText(text) <= maxWidth) {
            canvas.drawText(text, x, y, paint);
            return 0;
        }
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        float lineY = y;
        int extraLines = 0;

        for (String word : words) {
            String test = line.length() == 0 ? word : line + " " + word;
            if (paint.measureText(test) <= maxWidth) {
                line = new StringBuilder(test);
            } else {
                if (line.length() > 0) {
                    canvas.drawText(line.toString(), x, lineY, paint);
                    lineY += paint.getTextSize() + 4;
                    extraLines++;
                }
                line = new StringBuilder(word);
            }
        }
        if (line.length() > 0) {
            canvas.drawText(line.toString(), x, lineY, paint);
        }

        return extraLines;
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
