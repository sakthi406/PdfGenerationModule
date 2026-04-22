using System;
using System.Collections.Generic;

namespace PdfGenerationModule.Core
{
    /// <summary>
    /// Generic data model passed to the PDF generator.
    /// Projects populate this via IPdfDataProvider.
    /// This class has zero knowledge of what the data represents.
    /// </summary>
    [Serializable]
    public class PdfDocumentData
    {
        public string Title;
        public string SubTitle;
        public string RefDocumentId;
        public string FooterText;
        public string LogoBase64;
        public DateTime GeneratedAt;
        public List<PdfSection> Sections;

        public PdfDocumentData()
        {
            GeneratedAt = DateTime.Now;
            Sections = new List<PdfSection>();
        }
    }

    /// <summary>
    /// A named group of rows within the document.
    /// e.g. "Safety Checks", "Electrical Items"
    /// </summary>
    [Serializable]
    public class PdfSection
    {
        public string SectionTitle;
        public List<PdfRow> Rows;

        public PdfSection()
        {
            Rows = new List<PdfRow>();
        }
    }

    /// <summary>
    /// A single row: label on the left, value on the right.
    /// IsChecked is optional — used for checklist-style rows.
    /// </summary>
    [Serializable]
    public class PdfRow
    {
        public string Label;
        public string Value;
        public bool IsChecked;
        public bool HasCheckbox; // if false, render as plain text row
        public bool IsHeaderRow;
        public bool IsNoteRow;
    }
}
