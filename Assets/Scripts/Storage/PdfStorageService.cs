using System;
using System.IO;
using UnityEngine;
using PdfGenerationModule.Config;

namespace PdfGenerationModule.Storage
{
    /// <summary>
    /// Handles filename construction and Editor-side file cleanup.
    ///
    /// Storage PATH logic deliberately lives in PdfGeneratorPlugin.java:
    ///   API 29+ → MediaStore.Downloads (no permission, share-safe URI)
    ///   API 28−  → Environment.DIRECTORY_DOWNLOADS (direct path)
    ///
    /// This service only builds the filename — Java decides where it goes.
    /// </summary>
    public class PdfStorageService
    {
        private readonly PdfGeneratorConfig _config;

        public PdfStorageService(PdfGeneratorConfig config)
        {
            _config = config;
        }

        /// <summary>
        /// Builds a clean filename for the PDF.
        /// Full path resolution is handled by the Java plugin.
        /// </summary>
        public string BuildFileName(string documentTitle)
        {
            string safeName = SanitizeFileName(documentTitle);
            string timestamp = _config.AppendTimestamp
                ? "_" + DateTime.Now.ToString("yyyyMMdd_HHmmss")
                : string.Empty;

            return _config.FileNamePrefix + safeName + timestamp + ".pdf";
        }

        /// <summary>
        /// Editor-only: delete a file by full path.
        /// On device, files are in MediaStore — deletion handled separately.
        /// </summary>
        public void DeleteFile(string filePath)
        {
            if (File.Exists(filePath))
            {
                File.Delete(filePath);
                Debug.Log($"[PdfStorageService] Deleted: {filePath}");
            }
        }

        private string SanitizeFileName(string input)
        {
            if (string.IsNullOrWhiteSpace(input)) return "Document";
            foreach (char c in Path.GetInvalidFileNameChars())
                input = input.Replace(c.ToString(), "_");
            return input.Trim();
        }
    }
}