namespace PdfGenerationModule.Core
{
    /// <summary>
    /// Contract that any data source must implement to provide
    /// content to the PDF generator.
    /// 
    /// Your project implements this once.
    /// The PDF module never changes regardless of what data source you use.
    /// </summary>
    public interface IPdfDataProvider
    {
        /// <summary>
        /// Returns a fully populated PdfDocumentData ready for rendering.
        /// Called by PdfGeneratorService just before generation begins.
        /// </summary>
        PdfDocumentData GetDocumentData();
        string ProviderName { get; }
    }
}