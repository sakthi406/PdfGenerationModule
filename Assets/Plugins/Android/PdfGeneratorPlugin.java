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

public class PdfGeneratorPlugin
{
    private static final String TAG = "PdfGeneratorPlugin";

    // ── Table column proportions (out of content width) ──────────────
    private static final float COL_LABEL_RATIO  = 0.72f;
    private static final float COL_VALUE_RATIO  = 0.28f;

    // ── Colours ───────────────────────────────────────────────────────
    private static final int COLOR_HEADER_BG    = Color.parseColor("#1A3C5E"); // dark navy
    private static final int COLOR_HEADER_TEXT  = Color.WHITE;
    private static final int COLOR_ROW_ALT      = Color.parseColor("#F0F4F8"); // light blue-grey
    private static final int COLOR_ROW_NORMAL   = Color.WHITE;
    private static final int COLOR_NOTE_TEXT    = Color.parseColor("#555555");
    private static final int COLOR_BORDER       = Color.parseColor("#CCCCCC");
    private static final int COLOR_CHECKED      = Color.parseColor("#1A7A3C"); // green
    private static final int COLOR_UNCHECKED    = Color.parseColor("#C0392B"); // red
    private static final int COLOR_TITLE        = Color.parseColor("#1A3C5E");
    private static final int COLOR_SUBTITLE     = Color.parseColor("#666666");
    private static final int COLOR_FOOTER       = Color.parseColor("#999999");
    private static final int COLOR_DIVIDER      = Color.parseColor("#1A3C5E");

    // ── Logo dimensions ───────────────────────────────────────────────
    private static final int LOGO_MAX_W = 100;
    private static final int LOGO_MAX_H = 45;

    public static String generatePdf(
        Context context,
        String  fileName,
        String  jsonData,
        int     pageWidth,
        int     pageHeight,
        int     margin,
        int     titleFontSize,
        int     sectionFontSize,
        int     bodyFontSize,
        int     rowSpacing,
        int     sectionSpacing)
    {
        try
        {
            PdfDocument pdfDocument = buildPdfDocument(
                jsonData, pageWidth, pageHeight, margin,
                titleFontSize, sectionFontSize, bodyFontSize,
                rowSpacing, sectionSpacing);

            if (pdfDocument == null)
            {
                Log.e(TAG, "buildPdfDocument returned null");
                return "";
            }

            String resultPath;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                resultPath = saveViaMediaStore(context, pdfDocument, fileName);
            else
                resultPath = saveViaDirectPath(pdfDocument, fileName);

            pdfDocument.close();
            return resultPath != null ? resultPath : "";
        }
        catch (Exception e)
        {
            Log.e(TAG, "generatePdf failed: " + e.getMessage(), e);
            return "";
        }
    }

    // ── Save helpers (unchanged) ──────────────────────────────────────

    private static String saveViaMediaStore(
        Context context, PdfDocument pdf, String fileName)
    {
        try
        {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/pdf");
            values.put(MediaStore.Downloads.IS_PENDING, 1);

            Uri collection = MediaStore.Downloads.getContentUri(
                MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Uri fileUri = context.getContentResolver().insert(collection, values);

            if (fileUri == null)
            {
                Log.e(TAG, "MediaStore insert returned null URI");
                return null;
            }

            try (OutputStream os =
                     context.getContentResolver().openOutputStream(fileUri))
            {
                pdf.writeTo(os);
            }

            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            context.getContentResolver().update(fileUri, values, null, null);

            return fileUri.toString();
        }
        catch (Exception e)
        {
            Log.e(TAG, "saveViaMediaStore failed: " + e.getMessage(), e);
            return null;
        }
    }

    private static String saveViaDirectPath(PdfDocument pdf, String fileName)
    {
        try
        {
            File dir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists()) dir.mkdirs();

            File out = new File(dir, fileName);
            try (FileOutputStream fos = new FileOutputStream(out))
            {
                pdf.writeTo(fos);
            }
            return out.getAbsolutePath();
        }
        catch (Exception e)
        {
            Log.e(TAG, "saveViaDirectPath failed: " + e.getMessage(), e);
            return null;
        }
    }

    // ── Core builder ──────────────────────────────────────────────────

    private static PdfDocument buildPdfDocument(
        String jsonData,
        int pageWidth, int pageHeight, int margin,
        int titleFontSize, int sectionFontSize, int bodyFontSize,
        int rowSpacing, int sectionSpacing)
    {
        try
        {
            JSONObject doc    = new JSONObject(jsonData);
            String title      = doc.optString("Title",     "Report");
            String subTitle   = doc.optString("SubTitle",  "");
            String footerText = doc.optString("FooterText","");
            String logoBase64 = doc.optString("LogoBase64","");
            JSONArray sections = doc.optJSONArray("Sections");

            // ── Paints ────────────────────────────────────────────────
            Paint titlePaint    = makePaint(COLOR_TITLE,    titleFontSize,    Typeface.BOLD);
            Paint subTitlePaint = makePaint(COLOR_SUBTITLE, bodyFontSize + 1, Typeface.NORMAL);
            Paint footerPaint   = makePaint(COLOR_FOOTER,   bodyFontSize - 2, Typeface.ITALIC);

            Paint headerCellPaint = makePaint(COLOR_HEADER_TEXT, bodyFontSize, Typeface.BOLD);
            Paint bodyPaint       = makePaint(Color.parseColor("#222222"), bodyFontSize, Typeface.NORMAL);
            Paint notePaint       = makePaint(COLOR_NOTE_TEXT, bodyFontSize - 1, Typeface.ITALIC);

            Paint checkedPaint   = makePaint(COLOR_CHECKED,   bodyFontSize, Typeface.BOLD);
            Paint uncheckedPaint = makePaint(COLOR_UNCHECKED, bodyFontSize, Typeface.BOLD);

            Paint borderPaint = new Paint();
            borderPaint.setColor(COLOR_BORDER);
            borderPaint.setStrokeWidth(1f);
            borderPaint.setStyle(Paint.Style.STROKE);

            Paint dividerPaint = new Paint();
            dividerPaint.setColor(COLOR_DIVIDER);
            dividerPaint.setStrokeWidth(2f);

            // ── Logo decode ───────────────────────────────────────────
            Bitmap logoBitmap = null;
            if (!logoBase64.isEmpty())
            {
                try
                {
                    byte[] logoBytes = Base64.decode(logoBase64, Base64.DEFAULT);
                    logoBitmap = BitmapFactory.decodeByteArray(logoBytes, 0, logoBytes.length);
                }
                catch (Exception e)
                {
                    Log.w(TAG, "Logo decode failed: " + e.getMessage());
                }
            }

            // ── Page setup ────────────────────────────────────────────
            PdfDocument pdfDoc = new PdfDocument();
            PdfDocument.PageInfo pageInfo =
                new PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create();
            PdfDocument.Page page = pdfDoc.startPage(pageInfo);
            Canvas canvas = page.getCanvas();

            int contentW = pageWidth - (margin * 2);
            int colLabelW = (int)(contentW * COL_LABEL_RATIO);
            int colValueW = contentW - colLabelW;

            int y = margin + titleFontSize;

            // ── Logo top-right ────────────────────────────────────────
            if (logoBitmap != null)
            {
                int[] logoDims = scaledDims(
                    logoBitmap.getWidth(), logoBitmap.getHeight(),
                    LOGO_MAX_W, LOGO_MAX_H);

                int logoX = pageWidth - margin - logoDims[0];
                int logoY = margin;
                Rect destRect = new Rect(logoX, logoY,
                    logoX + logoDims[0], logoY + logoDims[1]);
                canvas.drawBitmap(logoBitmap, null, destRect, null);
            }

            // ── Title ─────────────────────────────────────────────────
            canvas.drawText(title, margin, y, titlePaint);
            y += (int)(rowSpacing * 1.2f);

            // ── SubTitle ──────────────────────────────────────────────
            if (!subTitle.isEmpty())
            {
                canvas.drawText(subTitle, margin, y, subTitlePaint);
                y += rowSpacing;
            }

            // ── Divider under header ───────────────────────────────────
            canvas.drawLine(margin, y, pageWidth - margin, y, dividerPaint);
            y += (int)(rowSpacing * 0.8f);

            // ── Sections ──────────────────────────────────────────────
            if (sections != null)
            {
                for (int s = 0; s < sections.length(); s++)
                {
                    JSONObject section   = sections.getJSONObject(s);
                    String sectionTitle  = section.optString("SectionTitle", "");
                    JSONArray rows       = section.optJSONArray("Rows");

                    if (!sectionTitle.isEmpty())
                    {
                        Paint sPaint = makePaint(COLOR_TITLE, sectionFontSize, Typeface.BOLD);
                        canvas.drawText(sectionTitle, margin, y, sPaint);
                        y += sectionFontSize + sectionSpacing;
                    }

                    if (rows != null)
                    {
                        boolean altRow = false; // alternating colour tracker

                        for (int r = 0; r < rows.length(); r++)
                        {
                            // ── Page overflow check ───────────────────
                            if (y + rowSpacing > pageHeight - (margin + rowSpacing * 2))
                            {
                                // Draw footer on current page
                                drawFooter(canvas, footerText, footerPaint,
                                    margin, pageWidth, pageHeight, rowSpacing, dividerPaint);

                                pdfDoc.finishPage(page);
                                PdfDocument.PageInfo np =
                                    new PdfDocument.PageInfo.Builder(
                                        pageWidth, pageHeight, pdfDoc.getPages().size() + 1).create();
                                page   = pdfDoc.startPage(np);
                                canvas = page.getCanvas();
                                y      = margin + bodyFontSize;
                            }

                            JSONObject row   = rows.getJSONObject(r);
                            String label     = row.optString("Label", "");
                            String value     = row.optString("Value", "");
                            boolean isHeader = row.optBoolean("IsHeaderRow", false);
                            boolean isNote   = row.optBoolean("IsNoteRow",   false);
                            boolean isChecked= row.optBoolean("IsChecked",   false);

                            int rowH = rowSpacing + 6; // row height with padding

                            if (isHeader)
                            {
                                // ── Header row — navy background ──────
                                Paint bgPaint = new Paint();
                                bgPaint.setColor(COLOR_HEADER_BG);
                                bgPaint.setStyle(Paint.Style.FILL);
                                canvas.drawRect(margin, y - bodyFontSize,
                                    pageWidth - margin, y - bodyFontSize + rowH, bgPaint);

                                canvas.drawText(label, margin + 6, y, headerCellPaint);
                                canvas.drawText(value,
                                    margin + colLabelW + 6, y, headerCellPaint);

                                // vertical divider between columns
                                canvas.drawLine(
                                    margin + colLabelW, y - bodyFontSize,
                                    margin + colLabelW, y - bodyFontSize + rowH,
                                    borderPaint);

                                altRow = false; // reset after header
                            }
                            else if (isNote)
                            {
                                // ── Note row — no background, italic ──
                                canvas.drawText(label, margin + 16, y, notePaint);
                            }
                            else
                            {
                                // ── Normal data row ───────────────────
                                Paint bgPaint = new Paint();
                                bgPaint.setColor(altRow ? COLOR_ROW_ALT : COLOR_ROW_NORMAL);
                                bgPaint.setStyle(Paint.Style.FILL);
                                canvas.drawRect(margin, y - bodyFontSize,
                                    pageWidth - margin, y - bodyFontSize + rowH, bgPaint);

                                // outer border
                                canvas.drawRect(margin, y - bodyFontSize,
                                    pageWidth - margin, y - bodyFontSize + rowH, borderPaint);

                                // label text — wrap long text
                                drawTextInColumn(canvas, label, margin + 6, y,
                                    colLabelW - 8, bodyPaint);

                                // value text — coloured by status
                                Paint valuePaint = isChecked ? checkedPaint : uncheckedPaint;
                                float vw = valuePaint.measureText(value);
                                canvas.drawText(value,
                                    margin + colLabelW + (colValueW - vw) / 2f,
                                    y, valuePaint);

                                // vertical column divider
                                canvas.drawLine(
                                    margin + colLabelW, y - bodyFontSize,
                                    margin + colLabelW, y - bodyFontSize + rowH,
                                    borderPaint);

                                altRow = !altRow;
                            }

                            y += rowH;
                        }
                    }
                    y += sectionSpacing;
                }
            }

            // ── Footer ────────────────────────────────────────────────
            drawFooter(canvas, footerText, footerPaint,
                margin, pageWidth, pageHeight, rowSpacing, dividerPaint);

            pdfDoc.finishPage(page);
            return pdfDoc;
        }
        catch (Exception e)
        {
            Log.e(TAG, "buildPdfDocument failed: " + e.getMessage(), e);
            return null;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private static void drawFooter(
        Canvas canvas, String text, Paint paint,
        int margin, int pageWidth, int pageHeight,
        int rowSpacing, Paint dividerPaint)
    {
        if (text == null || text.isEmpty()) return;
        int fy = pageHeight - margin;
        canvas.drawLine(margin, fy - rowSpacing,
            pageWidth - margin, fy - rowSpacing, dividerPaint);
        canvas.drawText(text, margin, fy, paint);
    }

    /// Draws text clipped to a column width — truncates with "…" if too long
    private static void drawTextInColumn(
        Canvas canvas, String text, float x, float y,
        int maxWidth, Paint paint)
    {
        if (paint.measureText(text) <= maxWidth)
        {
            canvas.drawText(text, x, y, paint);
            return;
        }
        // Truncate
        String ellipsis = "\u2026";
        while (text.length() > 1 &&
               paint.measureText(text + ellipsis) > maxWidth)
        {
            text = text.substring(0, text.length() - 1);
        }
        canvas.drawText(text + ellipsis, x, y, paint);
    }

    /// Scale dimensions preserving aspect ratio within max bounds
    private static int[] scaledDims(int srcW, int srcH, int maxW, int maxH)
    {
        float ratio = Math.min((float) maxW / srcW, (float) maxH / srcH);
        return new int[]{ (int)(srcW * ratio), (int)(srcH * ratio) };
    }

    private static Paint makePaint(int color, int size, int style)
    {
        Paint p = new Paint();
        p.setColor(color);
        p.setTextSize(size);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, style));
        p.setAntiAlias(true);
        return p;
    }
}