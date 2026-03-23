using UnityEngine;

namespace PdfGenerationModule.Config
{
    /// <summary>
    /// ScriptableObject config for the PDF generation module.
    /// Create one instance via:
    ///   Assets → Create → PdfGenerationModule → Generator Config
    /// Assign it to PdfGeneratorService in the Inspector.
    /// </summary>
    [CreateAssetMenu(
        fileName = "PdfGeneratorConfig",
        menuName = "PdfGenerationModule/Generator Config",
        order = 0)]
    public class PdfGeneratorConfig : ScriptableObject
    {
        [Header("Document Layout")]
        [Tooltip("Font size for the document title")]
        [Range(16, 32)]
        public int TitleFontSize = 22;

        [Tooltip("Font size for section headers")]
        [Range(12, 24)]
        public int SectionFontSize = 16;

        [Tooltip("Font size for body rows")]
        [Range(10, 18)]
        public int BodyFontSize = 12;

        [Header("Spacing")]
        [Tooltip("Left and right page margin in pixels")]
        [Range(20, 80)]
        public int PageMargin = 40;

        [Tooltip("Vertical space between rows in pixels")]
        [Range(10, 40)]
        public int RowSpacing = 24;

        [Tooltip("Extra space added after each section header")]
        [Range(4, 20)]
        public int SectionSpacing = 10;

        [Header("Page")]
        [Tooltip("PDF page width in pixels (A4 portrait ≈ 595)")]
        public int PageWidth = 595;

        [Tooltip("PDF page height in pixels (A4 portrait ≈ 842)")]
        public int PageHeight = 842;

        [Header("File")]
        [Tooltip("Prefix added to every generated PDF filename")]
        public string FileNamePrefix = "Report_";

        [Tooltip("Include timestamp in filename to avoid overwriting")]
        public bool AppendTimestamp = true;
    }
}