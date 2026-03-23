using UnityEngine;
using PdfGenerationModule.Config;
using PdfGenerationModule.Storage;

namespace PdfGenerationModule.Core
{
    /// <summary>
    /// Concrete implementation of IPdfGeneratorService.
    /// Bridges Unity C# → Android Java → PdfDocument API.
    /// 
    /// On non-Android platforms (Editor, iOS) it logs a warning
    /// and returns null — no crash, graceful degradation.
    /// </summary>
    public class PdfGeneratorService : IPdfGeneratorService
    {
        private const string JavaPluginClass =
            "com.pdfgenerationmodule.PdfGeneratorPlugin";

        private readonly PdfGeneratorConfig _config;
        private readonly PdfStorageService _storageService;

        public bool IsPlatformSupported =>
            Application.platform == RuntimePlatform.Android;

        public PdfGeneratorService(
            PdfGeneratorConfig config,
            PdfStorageService storageService)
        {
            _config = config;
            _storageService = storageService;
        }

        /// <summary>
        /// Generates a PDF from the provided data source.
        /// Returns the saved file path on success, null on failure.
        /// </summary>
        public string Generate(IPdfDataProvider dataProvider)
        {
            if (dataProvider == null)
            {
                Debug.LogError("[PdfGeneratorService] dataProvider is null.");
                return null;
            }

            if (!IsPlatformSupported)
            {
                Debug.LogWarning(
                    "[PdfGeneratorService] Android only. " +
                    $"Provider '{dataProvider.ProviderName}' ready for device build.");
                return null;
            }

            PdfDocumentData data = dataProvider.GetDocumentData();
            if (data == null)
            {
                Debug.LogError("[PdfGeneratorService] Provider returned null data.");
                return null;
            }

            // Build filename only — storage location decided by Java based on API level
            string fileName = _storageService.BuildFileName(data.Title);
            string jsonData = JsonUtility.ToJson(data, prettyPrint: false);

            Debug.Log($"[PdfGeneratorService] Generating: {fileName}");

            string result = CallAndroidPlugin(fileName, jsonData);

            if (!string.IsNullOrEmpty(result))
            {
                Debug.Log($"[PdfGeneratorService] Success → {result}");
                return result;
            }

            Debug.LogError("[PdfGeneratorService] Generation failed.");
            return null;
        }

        // ─── Private Helpers ────────────────────────────────────────────

        private string SerializeToJson(PdfDocumentData data)
        {
            return JsonUtility.ToJson(data, prettyPrint: false);
        }

        private string CallAndroidPlugin(string fileName, string jsonData)
        {
            try
            {
                // Get Unity's Android Activity (context) — standard Unity pattern
                using var unityPlayer = new AndroidJavaClass(
                    "com.unity3d.player.UnityPlayer");
                using var activity = unityPlayer.GetStatic<AndroidJavaObject>(
                    "currentActivity");
                using var pluginClass = new AndroidJavaClass(
                    JavaPluginClass);

                string result = pluginClass.CallStatic<string>(
                    "generatePdf",
                    activity,           // Context — required for MediaStore
                    fileName,           // just the filename, not full path
                    jsonData,
                    _config.PageWidth,
                    _config.PageHeight,
                    _config.PageMargin,
                    _config.TitleFontSize,
                    _config.SectionFontSize,
                    _config.BodyFontSize,
                    _config.RowSpacing,
                    _config.SectionSpacing);

                return string.IsNullOrEmpty(result) ? null : result;
            }
            catch (System.Exception ex)
            {
                Debug.LogError(
                    $"[PdfGeneratorService] Android plugin call failed: {ex.Message}");
                return null;
            }
        }
    }
}