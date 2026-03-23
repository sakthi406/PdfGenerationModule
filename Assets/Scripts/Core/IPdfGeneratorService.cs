using System;

namespace PdfGenerationModule.Core
{
    /// <summary>
    /// Contract for the PDF generation service.
    /// Concrete implementation uses Android PdfDocument API.
    /// A mock implementation can be used in Editor / unit tests.
    /// </summary>
    public interface IPdfGeneratorService
    {
        /// <summary>
        /// Generates a PDF from the given data provider.
        /// Returns the absolute file path of the saved PDF on success.
        /// Returns null on failure.
        /// </summary>
        string Generate(IPdfDataProvider dataProvider);

        /// <summary>
        /// True if the current platform supports native PDF generation.
        /// False in Unity Editor or unsupported platforms.
        /// </summary>
        bool IsPlatformSupported { get; }
    }
}