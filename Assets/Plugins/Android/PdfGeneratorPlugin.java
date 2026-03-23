package com.pdfgenerationmodule;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

//Android plugin for generating PDF documents from JSON data.

public class PdfGeneratorPlugin
{
    private static final String TAG = "PdfGeneratorPlugin";

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
            // ── Build the PDF document in memory ─────────────────────
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) // API 29+
            {
                resultPath = saveViaMediaStore(context, pdfDocument, fileName);
            }
            else
            {
                resultPath = saveViaDirectPath(pdfDocument, fileName);
            }

            pdfDocument.close();
            return resultPath != null ? resultPath : "";
        }
        catch (Exception e)
        {
            Log.e(TAG, "generatePdf failed: " + e.getMessage(), e);
            return "";
        }
    }

    private static String saveViaMediaStore(
        Context context, PdfDocument pdf, String fileName)
    {
        try
        {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/pdf");
            values.put(MediaStore.Downloads.IS_PENDING, 1); // lock file while writing

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

            // Mark file as complete — makes it visible in Files app
            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            context.getContentResolver().update(fileUri, values, null, null);

            Log.d(TAG, "MediaStore save success: " + fileUri.toString());
            return fileUri.toString(); // content:// URI — safe to share
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
            File downloadsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);

            if (!downloadsDir.exists())
                downloadsDir.mkdirs();

            File outputFile = new File(downloadsDir, fileName);

            try (FileOutputStream fos = new FileOutputStream(outputFile))
            {
                pdf.writeTo(fos);
            }

            Log.d(TAG, "Direct path save success: " + outputFile.getAbsolutePath());
            return outputFile.getAbsolutePath();
        }
        catch (Exception e)
        {
            Log.e(TAG, "saveViaDirectPath failed: " + e.getMessage(), e);
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // PDF Document Builder
    // ─────────────────────────────────────────────────────────────────
    private static PdfDocument buildPdfDocument(
        String jsonData,
        int pageWidth, int pageHeight, int margin,
        int titleFontSize, int sectionFontSize, int bodyFontSize,
        int rowSpacing, int sectionSpacing)
    {
        try
        {
            JSONObject doc     = new JSONObject(jsonData);
            String title       = doc.optString("Title",     "Report");
            String subTitle    = doc.optString("SubTitle",  "");
            String footerText  = doc.optString("FooterText","");
            JSONArray sections = doc.optJSONArray("Sections");

            // Paints
            Paint titlePaint = makePaint(Color.BLACK,
                titleFontSize, Typeface.BOLD, true);
            Paint sectionPaint = makePaint(Color.parseColor("#333333"),
                sectionFontSize, Typeface.BOLD, true);
            Paint bodyPaint = makePaint(Color.parseColor("#444444"),
                bodyFontSize, Typeface.NORMAL, true);
            Paint subTitlePaint = makePaint(Color.parseColor("#666666"),
                bodyFontSize + 2, Typeface.NORMAL, true);
            Paint footerPaint = makePaint(Color.parseColor("#999999"),
                bodyFontSize - 2, Typeface.NORMAL, true);
            Paint linePaint = new Paint();
            linePaint.setColor(Color.parseColor("#CCCCCC"));
            linePaint.setStrokeWidth(1f);

            PdfDocument pdfDoc = new PdfDocument();
            PdfDocument.PageInfo pageInfo =
                new PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create();
            PdfDocument.Page page = pdfDoc.startPage(pageInfo);
            Canvas canvas = page.getCanvas();

            int y = margin + titleFontSize;
            int contentW = pageWidth - (margin * 2);

            // Title
            canvas.drawText(title, margin, y, titlePaint);
            y += rowSpacing;

            // SubTitle
            if (!subTitle.isEmpty()) {
                canvas.drawText(subTitle, margin, y, subTitlePaint);
                y += rowSpacing;
            }

            // Header divider
            canvas.drawLine(margin, y, pageWidth - margin, y, linePaint);
            y += rowSpacing;

            // Sections
            if (sections != null)
            {
                for (int s = 0; s < sections.length(); s++)
                {
                    // Page overflow
                    if (y > pageHeight - (margin + rowSpacing * 3))
                    {
                        pdfDoc.finishPage(page);
                        PdfDocument.PageInfo next =
                            new PdfDocument.PageInfo.Builder(
                                pageWidth, pageHeight, s + 2).create();
                        page   = pdfDoc.startPage(next);
                        canvas = page.getCanvas();
                        y      = margin + sectionFontSize;
                    }

                    JSONObject section  = sections.getJSONObject(s);
                    String sectionTitle = section.optString("SectionTitle","");
                    JSONArray rows      = section.optJSONArray("Rows");

                    if (!sectionTitle.isEmpty())
                    {
                        canvas.drawText(sectionTitle, margin, y, sectionPaint);
                        y += sectionSpacing + sectionFontSize;
                        canvas.drawLine(margin, y,
                            margin + contentW / 3, y, linePaint);
                        y += rowSpacing;
                    }

                    if (rows != null)
                    {
                        for (int r = 0; r < rows.length(); r++)
                        {
                            if (y > pageHeight - (margin + rowSpacing * 2))
                            {
                                pdfDoc.finishPage(page);
                                PdfDocument.PageInfo rPage =
                                    new PdfDocument.PageInfo.Builder(
                                        pageWidth, pageHeight, s + r + 2).create();
                                page   = pdfDoc.startPage(rPage);
                                canvas = page.getCanvas();
                                y      = margin + bodyFontSize;
                            }

                            JSONObject row     = rows.getJSONObject(r);
                            String label       = row.optString("Label","");
                            String value       = row.optString("Value","");
                            boolean isChecked  = row.optBoolean("IsChecked", false);
                            boolean hasCheck   = row.optBoolean("HasCheckbox", false);

                            if (hasCheck)
                            {
                                canvas.drawText(
                                    (isChecked ? "[+] " : "[ ] ") + label,
                                    margin, y, bodyPaint);
                            }
                            else
                            {
                                canvas.drawText(label, margin, y, bodyPaint);
                                if (!value.isEmpty())
                                {
                                    float vw = bodyPaint.measureText(value);
                                    canvas.drawText(value,
                                        pageWidth - margin - vw, y, bodyPaint);
                                }
                            }
                            y += rowSpacing;
                        }
                    }
                    y += sectionSpacing;
                }
            }

            // Footer
            if (!footerText.isEmpty())
            {
                canvas.drawLine(margin,
                    pageHeight - margin - rowSpacing,
                    pageWidth - margin,
                    pageHeight - margin - rowSpacing, linePaint);
                canvas.drawText(footerText,
                    margin, pageHeight - margin, footerPaint);
            }

            pdfDoc.finishPage(page);
            return pdfDoc;
        }
        catch (Exception e)
        {
            Log.e(TAG, "buildPdfDocument failed: " + e.getMessage(), e);
            return null;
        }
    }

    private static Paint makePaint(int color, int size, int style, boolean aa)
    {
        Paint p = new Paint();
        p.setColor(color);
        p.setTextSize(size);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, style));
        p.setAntiAlias(aa);
        return p;
    }
}