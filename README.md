# PdfGenerationModule

> A reusable, modular Unity submodule for native Android PDF generation.

---

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Folder Structure](#folder-structure)
- [Requirements](#requirements)
- [Quick Start — Add to Your Project](#quick-start--add-to-your-project)
- [Step-by-Step Integration Guide](#step-by-step-integration-guide)
  - [Step 1 — Add as Git Submodule](#step-1--add-as-git-submodule)
  - [Step 2 — Configure the ScriptableObject](#step-2--configure-the-scriptableobject)
  - [Step 3 — Implement IPdfDataProvider](#step-3--implement-ipdfdataprovider)
  - [Step 4 — Call the Generator](#step-4--call-the-generator)
- [API Reference](#api-reference)
  - [IPdfDataProvider](#ipdfdataprovider)
  - [IPdfGeneratorService](#ipdfgeneratorservice)
  - [PdfGeneratorService](#pdfgeneratorservice)
  - [PdfStorageService](#pdfstorageservice)
  - [PdfDocumentData](#pdfdocumentdata)
  - [PdfSection](#pdfsection)
  - [PdfRow](#pdfrow)
  - [PdfGeneratorConfig](#pdfgeneratorconfig)
- [JSON Contract](#json-contract)
- [PDF Visual Customisation](#pdf-visual-customisation)
- [Platform Support & Storage Strategy](#platform-support--storage-strategy)
- [Demo Scene](#demo-scene)
- [What To Change Per Project](#what-to-change-per-project)
- [What Never Changes](#what-never-changes)
- [SOLID Principles Applied](#solid-principles-applied)

---

## Overview

`PdfGenerationModule` is a plug-and-play Unity submodule that converts any structured data into a formatted PDF file on Android devices. It uses Android's built-in `PdfDocument` API called from Unity via `AndroidJavaObject` — the same native bridge pattern used in the companion `ExternalAppDeepLinking` submodule.

The module is **completely decoupled from your application's data model**. It does not know what a checklist, invoice, or inspection report is. It only knows how to render a generic document structure into a PDF. You provide the data — it provides the file.

---

## Architecture

```
Your Project
    │
    │  implements
    ▼
IPdfDataProvider          ← YOUR only integration point
    │
    │  GetDocumentData() returns PdfDocumentData
    ▼
PdfGeneratorService       ← C# bridge (never modify)
    │
    │  JsonUtility.ToJson(data)
    ▼
JSON string               ← decoupled data contract
    │
    │  AndroidJavaObject → CallStatic("generatePdf", ...)
    ▼
PdfGeneratorPlugin.java   ← Android native plugin (never modify)
    │
    │  API 29+: MediaStore.Downloads (no permission needed)
    │  API 28−: Environment.DIRECTORY_DOWNLOADS (permission on old devices)
    ▼
PDF saved to Downloads folder
    │
    │  returns file path / content URI
    ▼
Your Share Service / File Manager
```

---

## Folder Structure

```
Assets/PdfGenerationModule/
│
├── Scripts/
│   ├── Core/
│   │   ├── IPdfDataProvider.cs         ← Interface: implement this in your project
│   │   ├── IPdfGeneratorService.cs     ← Interface: generator contract
│   │   ├── PdfDocumentData.cs          ← Generic data model (Title, Sections, Rows)
│   │   └── PdfGeneratorService.cs      ← Concrete Android bridge implementation
│   │
│   ├── Storage/
│   │   └── PdfStorageService.cs        ← Filename builder and file cleanup utility
│   │
│   ├── Config/
│   │   └── PdfGeneratorConfig.cs       ← ScriptableObject: all visual settings
│   │
│   └── Demo/
│       └── PdfGeneratorDemo.cs         ← Demo scene controller + mock data provider
│
├── Plugins/
│   └── Android/
│       ├── PdfGeneratorPlugin.java     ← Native Android PdfDocument implementation
│       └── pdfgenerator.androidlib/
│           ├── AndroidManifest.xml     ← FileProvider declaration + permissions
│           └── res/xml/
│               └── file_paths.xml      ← FileProvider path configuration
│
├── Prefabs/
│   ├── DemoManager.prefab              ← Drag into any scene to run the demo
│   └── PdfGeneratorDemoCanvas.prefab   ← Demo UI canvas
│
├── Scenes/
│   └── PdfGeneratorDemoScene.unity     ← Fully wired demo scene
│
└── README.md
```

---

## Requirements

| Requirement         | Value                                                                        |
| ------------------- | ---------------------------------------------------------------------------- |
| Unity Version       | Unity 6 (2023+) or later                                                     |
| Android Minimum API | API 21 (Android 5.0 Lollipop)                                                |
| Android Target API  | API 33+ recommended                                                          |
| Scripting Backend   | IL2CPP (recommended) or Mono                                                 |
| .NET Compatibility  | .NET Standard 2.1                                                            |
| External Packages   | **None**                                                                     |
| Runtime Permissions | None on API 29+. WRITE_EXTERNAL_STORAGE on API 28 and below (auto-requested) |

---

## Quick Start — Add to Your Project

```bash
# 1. Add as a git submodule inside your Unity project's Assets folder
git submodule add https://github.com/sakthi406/PdfGenerationModule Assets/PdfGenerationModule

# 2. Initialise submodule
git submodule update --init --recursive
```

Then in Unity:

1. Right-click `Assets/PdfGenerationModule/` → **Create → PdfGenerationModule → Generator Config**
2. Implement `IPdfDataProvider` in your project (see Step 3 below)
3. Call `PdfGeneratorService.Generate(yourProvider)` — done

---

## Step-by-Step Integration Guide

### Step 1 — Add as Git Submodule

```bash
cd YourUnityProject/
git submodule add https://github.com/sakthi406/PdfGenerationModule Assets/PdfGenerationModule
git submodule update --init --recursive
```

Open Unity. The module will import automatically. You will see no compile errors.

---

### Step 2 — Configure the ScriptableObject

In the Unity **Project window**:

```
Right-click → Create → PdfGenerationModule → Generator Config
```

Name the asset `PdfGeneratorConfig` and place it inside your project's `Resources/` folder so it can be loaded at runtime via `Resources.Load<PdfGeneratorConfig>("PdfGeneratorConfig")`.

Click the asset and configure it in the Inspector:

| Field             | Default     | Description                                     |
| ----------------- | ----------- | ----------------------------------------------- |
| `TitleFontSize`   | 22          | Font size of the PDF document title             |
| `SectionFontSize` | 16          | Font size of each section header                |
| `BodyFontSize`    | 12          | Font size of all row content                    |
| `PageMargin`      | 40          | Left and right page margin in pixels            |
| `RowSpacing`      | 24          | Vertical gap between each row in pixels         |
| `SectionSpacing`  | 10          | Extra vertical gap added after section headers  |
| `PageWidth`       | 595         | PDF page width in pixels (A4 portrait = 595)    |
| `PageHeight`      | 842         | PDF page height in pixels (A4 portrait = 842)   |
| `FileNamePrefix`  | `"Report_"` | Prefix added to every generated PDF filename    |
| `AppendTimestamp` | `true`      | If true, appends `_yyyyMMdd_HHmmss` to filename |

> **These are the only settings you need to touch for most projects.** No code changes required for layout adjustments.

---

### Step 3 — Implement IPdfDataProvider

This is the **only class you write** in your project to use this module. Create it anywhere in your project — it does not need to be inside the submodule folder.

```csharp
using PdfGenerationModule.Core;
using System.Collections.Generic;

// Example: adapting a checklist model to the PDF module
public class ChecklistDataAdapter : IPdfDataProvider
{
    private readonly ChecklistModel _source;

    // Constructor — inject your real data
    public ChecklistDataAdapter(ChecklistModel source)
    {
        _source = source;
    }

    // Required by IPdfDataProvider — used in logs and debug output
    public string ProviderName => "ChecklistDataAdapter";

    // Required by IPdfDataProvider — return your data as PdfDocumentData
    public PdfDocumentData GetDocumentData()
    {
        var doc = new PdfDocumentData
        {
            Title      = "Inspection Report — " + _source.ChecklistName,
            SubTitle   = "Inspector: " + _source.InspectorName +
                         "  |  Date: " + System.DateTime.Now.ToString("dd MMM yyyy"),
            FooterText = "Generated by " + _source.AppName + " — Confidential"
        };

        // Map each category in your data to a PdfSection
        foreach (var category in _source.Categories)
        {
            var section = new PdfSection
            {
                SectionTitle = category.Name
            };

            // Map each item in the category to a PdfRow
            foreach (var item in category.Items)
            {
                section.Rows.Add(new PdfRow
                {
                    Label       = item.Name,
                    IsChecked   = item.IsComplete,
                    HasCheckbox = true       // renders as [✓] or [ ]
                });
            }

            doc.Sections.Add(section);
        }

        return doc;
    }
}
```

**For non-checklist data (e.g. an inspection with measured values):**

```csharp
section.Rows.Add(new PdfRow
{
    Label       = "Valve Pressure",
    Value       = "4.2 bar",     // shown right-aligned on the same row
    HasCheckbox = false           // renders as: Label .............. Value
});
```

---

### Step 4 — Call the Generator

```csharp
using PdfGenerationModule.Core;
using PdfGenerationModule.Config;
using PdfGenerationModule.Storage;
using UnityEngine;

public class ShareController : MonoBehaviour
{
    private void GenerateAndSharePdf(ChecklistModel checklistData)
    {
        // Load config from Resources folder
        var config  = Resources.Load<PdfGeneratorConfig>("PdfGeneratorConfig");

        // Instantiate services
        var storage   = new PdfStorageService(config);
        var generator = new PdfGeneratorService(config, storage);

        // Wrap your data in the adapter
        var provider  = new ChecklistDataAdapter(checklistData);

        // Generate — returns file path or content URI on success, null on failure
        string pdfPath = generator.Generate(provider);

        if (!string.IsNullOrEmpty(pdfPath))
        {
            Debug.Log("PDF ready: " + pdfPath);
            // Pass pdfPath to your Share Service (next submodule)
        }
        else
        {
            Debug.LogError("PDF generation failed. Check console for details.");
        }
    }
}
```

---

## API Reference

### `IPdfDataProvider`

**Namespace:** `PdfGenerationModule.Core`  
**File:** `Scripts/Core/IPdfDataProvider.cs`

The contract your project must implement. This is the **single integration point** between your application and the PDF module.

| Member              | Type                | Description                                                                                                |
| ------------------- | ------------------- | ---------------------------------------------------------------------------------------------------------- |
| `ProviderName`      | `string` (property) | Human-readable name used in log output. Return any descriptive string.                                     |
| `GetDocumentData()` | `PdfDocumentData`   | Called by `PdfGeneratorService` just before generation. Return a fully populated `PdfDocumentData` object. |

---

### `IPdfGeneratorService`

**Namespace:** `PdfGenerationModule.Core`  
**File:** `Scripts/Core/IPdfGeneratorService.cs`

The contract for the generator itself. Allows mocking in Editor tests.

| Member                                | Type              | Description                                                                                                                                     |
| ------------------------------------- | ----------------- | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| `Generate(IPdfDataProvider provider)` | `string`          | Runs the full generation pipeline. Returns the saved file path (API 28−) or content URI string (API 29+) on success. Returns `null` on failure. |
| `IsPlatformSupported`                 | `bool` (property) | Returns `true` on Android device. Returns `false` in Unity Editor — generation is skipped gracefully, no crash.                                 |

---

### `PdfGeneratorService`

**Namespace:** `PdfGenerationModule.Core`  
**File:** `Scripts/Core/PdfGeneratorService.cs`  
**Implements:** `IPdfGeneratorService`

The concrete implementation. Bridges Unity C# to Android Java via `AndroidJavaObject`.

**Constructor:**

```csharp
new PdfGeneratorService(PdfGeneratorConfig config, PdfStorageService storageService)
```

**Methods:**

| Method                                | Returns  | Description                                                                                                                               |
| ------------------------------------- | -------- | ----------------------------------------------------------------------------------------------------------------------------------------- |
| `Generate(IPdfDataProvider provider)` | `string` | Full pipeline: validates input → gets data → serialises to JSON → calls Java plugin → returns result path. Returns `null` on any failure. |

**Internal flow when `Generate()` is called:**

1. Validates `provider` is not null
2. Checks `IsPlatformSupported` — returns null with log warning if in Editor
3. Calls `provider.GetDocumentData()` to get `PdfDocumentData`
4. Calls `storageService.BuildFileName(data.Title)` to get filename
5. Serialises data to JSON via `JsonUtility.ToJson()`
6. Calls `PdfGeneratorPlugin.java` via `AndroidJavaClass.CallStatic()`
7. Returns the result string (file path or content URI)

---

### `PdfStorageService`

**Namespace:** `PdfGenerationModule.Storage`  
**File:** `Scripts/Storage/PdfStorageService.cs`

Handles filename construction and Editor-side file cleanup. Storage path logic lives in the Java plugin.

**Constructor:**

```csharp
new PdfStorageService(PdfGeneratorConfig config)
```

**Methods:**

| Method                                | Returns  | Description                                                                                                                               |
| ------------------------------------- | -------- | ----------------------------------------------------------------------------------------------------------------------------------------- |
| `BuildFileName(string documentTitle)` | `string` | Builds a sanitised filename using the config prefix and optional timestamp. Example output: `Report_SafetyInspection_20250322_143012.pdf` |
| `DeleteFile(string filePath)`         | `void`   | Deletes a file at the given path if it exists. Safe to call even if file does not exist. Primarily used in Editor and demo scenes.        |

---

### `PdfDocumentData`

**Namespace:** `PdfGenerationModule.Core`  
**File:** `Scripts/Core/PdfDocumentData.cs`

The root data model passed to the generator. Fill this in `GetDocumentData()`.

| Field         | Type               | Description                                                               |
| ------------- | ------------------ | ------------------------------------------------------------------------- |
| `Title`       | `string`           | Main document title — rendered large at the top of the PDF                |
| `SubTitle`    | `string`           | Secondary line below the title — good for metadata (date, inspector name) |
| `FooterText`  | `string`           | Text rendered at the bottom of every page                                 |
| `GeneratedAt` | `DateTime`         | Auto-set to `DateTime.Now` on construction — can be overridden            |
| `Sections`    | `List<PdfSection>` | Ordered list of content sections. Auto-initialised to empty list.         |

---

### `PdfSection`

**Namespace:** `PdfGenerationModule.Core`  
**File:** `Scripts/Core/PdfDocumentData.cs`

A named group of rows within the document. Maps to a bold section header in the PDF.

| Field          | Type           | Description                                                                                |
| -------------- | -------------- | ------------------------------------------------------------------------------------------ |
| `SectionTitle` | `string`       | Header text rendered in bold above the section's rows. Leave empty to suppress the header. |
| `Rows`         | `List<PdfRow>` | Ordered list of rows in this section. Auto-initialised to empty list.                      |

---

### `PdfRow`

**Namespace:** `PdfGenerationModule.Core`  
**File:** `Scripts/Core/PdfDocumentData.cs`

A single content row. Renders differently based on `HasCheckbox`.

| Field         | Type     | Description                                                                                                                               |
| ------------- | -------- | ----------------------------------------------------------------------------------------------------------------------------------------- |
| `Label`       | `string` | Left-side text. Always shown.                                                                                                             |
| `Value`       | `string` | Right-aligned text. Only shown if `HasCheckbox` is `false`.                                                                               |
| `IsChecked`   | `bool`   | If `HasCheckbox` is `true`, renders `[+]` when true or `[ ]` when false.                                                                  |
| `HasCheckbox` | `bool`   | **`true`** → renders as `[+] Label` or `[ ] Label` (checklist style). **`false`** → renders as `Label ......... Value` (key-value style). |

**Row rendering examples:**

```
HasCheckbox = true,  IsChecked = true   →   [+] Main circuit breaker
HasCheckbox = true,  IsChecked = false  →   [ ] Earthing connection
HasCheckbox = false, Value = "4.2 bar"  →   Valve Pressure ............... 4.2 bar
HasCheckbox = false, Value = ""         →   Inspector Name
```

---

### `PdfGeneratorConfig`

**Namespace:** `PdfGenerationModule.Config`  
**File:** `Scripts/Config/PdfGeneratorConfig.cs`  
**Type:** `ScriptableObject`

Create via: `Right-click → Create → PdfGenerationModule → Generator Config`

All fields are exposed in the Unity Inspector. No code changes needed for layout adjustments.

| Field             | Type     | Range | Default     | Description                            |
| ----------------- | -------- | ----- | ----------- | -------------------------------------- |
| `TitleFontSize`   | `int`    | 16–32 | 22          | Document title font size               |
| `SectionFontSize` | `int`    | 12–24 | 16          | Section header font size               |
| `BodyFontSize`    | `int`    | 10–18 | 12          | Row text font size                     |
| `PageMargin`      | `int`    | 20–80 | 40          | Left and right margin in pixels        |
| `RowSpacing`      | `int`    | 10–40 | 24          | Vertical gap between rows              |
| `SectionSpacing`  | `int`    | 4–20  | 10          | Extra gap after section headers        |
| `PageWidth`       | `int`    | —     | 595         | Page width in pixels (A4 = 595)        |
| `PageHeight`      | `int`    | —     | 842         | Page height in pixels (A4 = 842)       |
| `FileNamePrefix`  | `string` | —     | `"Report_"` | Prefix on every generated filename     |
| `AppendTimestamp` | `bool`   | —     | `true`      | Appends `_yyyyMMdd_HHmmss` to filename |

---

## JSON Contract

Before crossing from C# to Android Java, `PdfDocumentData` is serialised to JSON via `JsonUtility.ToJson()`. This is the exact structure the Java plugin receives:

```json
{
  "Title": "Safety Inspection Checklist",
  "SubTitle": "Site: Block A  |  Inspector: Demo User  |  Date: 22 Mar 2025",
  "FooterText": "Generated by PdfGenerationModule — Confidential",
  "GeneratedAt": "2025-03-22T14:30:12",
  "Sections": [
    {
      "SectionTitle": "Electrical Checks",
      "Rows": [
        {
          "Label": "Main circuit breaker",
          "Value": "",
          "IsChecked": true,
          "HasCheckbox": true
        },
        {
          "Label": "Emergency lighting",
          "Value": "",
          "IsChecked": true,
          "HasCheckbox": true
        },
        {
          "Label": "Earthing connection",
          "Value": "",
          "IsChecked": false,
          "HasCheckbox": true
        }
      ]
    },
    {
      "SectionTitle": "Mechanical Checks",
      "Rows": [
        {
          "Label": "Valve pressure",
          "Value": "4.2 bar",
          "IsChecked": false,
          "HasCheckbox": false
        },
        {
          "Label": "Pump RPM",
          "Value": "1450",
          "IsChecked": false,
          "HasCheckbox": false
        },
        {
          "Label": "Gasket condition",
          "Value": "Replace",
          "IsChecked": false,
          "HasCheckbox": false
        }
      ]
    }
  ]
}
```

> This JSON contract is what makes the module decoupled. The Java plugin is completely independent of Unity's type system. Any language that can produce this JSON structure can drive this module.

---

## PDF Visual Customisation

### Level 1 — Zero code (Inspector only)

Adjust `PdfGeneratorConfig` fields in the Inspector: font sizes, margins, spacing, page dimensions.

### Level 2 — Java plugin (colours, borders, layout style)

Open `Plugins/Android/PdfGeneratorPlugin.java` and edit the paint values inside `buildPdfDocument()`:

**Change title colour:**

```java
// Find this line in buildPdfDocument()
Paint titlePaint = makePaint(Color.BLACK, titleFontSize, Typeface.BOLD, true);

// Change to any hex colour:
Paint titlePaint = makePaint(Color.parseColor("#1A237E"), titleFontSize, Typeface.BOLD, true);
```

**Change section header background:**

```java
// Add before canvas.drawText(sectionTitle, ...)
Paint sectionBgPaint = new Paint();
sectionBgPaint.setColor(Color.parseColor("#E8EAF6"));
canvas.drawRect(margin, y - sectionFontSize - 4, pageWidth - margin, y + 8, sectionBgPaint);
```

**Change checkbox symbols:**

```java
// Find this line:
String checkSymbol = isChecked ? "[+] " : "[ ] ";

// Change to any characters you prefer:
String checkSymbol = isChecked ? "✓  " : "○  ";
```

**Change page size to Letter (US):**

```
In PdfGeneratorConfig Inspector:
  PageWidth  → 612
  PageHeight → 792
```

---

## Platform Support & Storage Strategy

| Platform                        | Status            | Storage Method                                                              |
| ------------------------------- | ----------------- | --------------------------------------------------------------------------- |
| Android API 29+ (Android 10+)   | ✅ Full support   | `MediaStore.Downloads` — no permission, returns `content://` URI            |
| Android API 21–28 (Android 5–9) | ✅ Full support   | `Environment.DIRECTORY_DOWNLOADS` — `WRITE_EXTERNAL_STORAGE` auto-requested |
| Unity Editor (any OS)           | ⚠️ Graceful no-op | Logs a platform warning. Returns `null`. No crash.                          |
| iOS                             | 🔜 Planned        | Will use `PDFKit` via a separate native bridge                              |

**Why two storage strategies?**

Android 10 (API 29) introduced Scoped Storage, which removed direct file path access to shared storage. Google's recommended approach is `MediaStore` for API 29+, which returns a `content://` URI — this is also what the Share Service submodule expects for intent-based sharing. The direct path fallback handles older devices transparently.

---

## Demo Scene

Open `Scenes/PdfGeneratorDemoScene.unity` to see the module in action with mock checklist data.

**UI Buttons:**

| Button           | Behaviour                                                                                                                                 |
| ---------------- | ----------------------------------------------------------------------------------------------------------------------------------------- |
| **Preview Data** | Opens an overlay showing the exact JSON that will be sent to the Android plugin. Lets you verify data structure before generating.        |
| **Generate PDF** | Runs the full pipeline. On an Android device, saves a real PDF to the Downloads folder. In the Editor, shows a graceful platform warning. |
| **Clear File**   | Deletes the last generated file from device storage. Becomes active only after a successful generation.                                   |

**Mock data the demo generates:**

The demo uses `MockChecklistDataProvider` which produces a three-section Safety Inspection report:

- **Electrical Checks** — 4 checkbox rows
- **Mechanical Checks** — 4 key-value rows
- **Safety Equipment** — 3 checkbox rows

This demonstrates both `HasCheckbox = true` and `HasCheckbox = false` row types in the same document.

**Where the PDF appears on device:**

```
Files app → Downloads → Report_SafetyInspectionChecklist_20250322_143012.pdf
```

---

## What To Change Per Project

| What you want                             | What to change                                 | Where                                         |
| ----------------------------------------- | ---------------------------------------------- | --------------------------------------------- |
| Connect your real data                    | Implement `IPdfDataProvider`                   | Your project (new file)                       |
| Change document title / subtitle / footer | Populate `PdfDocumentData` fields              | Inside your `IPdfDataProvider` implementation |
| Add / remove sections                     | Add / remove `PdfSection` objects              | Inside your `IPdfDataProvider` implementation |
| Change font sizes and spacing             | Edit `PdfGeneratorConfig` asset                | Unity Inspector (no code)                     |
| Change page size                          | Edit `PageWidth` / `PageHeight` in config      | Unity Inspector (no code)                     |
| Change filename prefix                    | Edit `FileNamePrefix` in config                | Unity Inspector (no code)                     |
| Change PDF text colours                   | Edit `makePaint()` calls in Java plugin        | `PdfGeneratorPlugin.java`                     |
| Add coloured section backgrounds          | Add `canvas.drawRect()` before section headers | `PdfGeneratorPlugin.java`                     |
| Add a company logo                        | Add `canvas.drawBitmap()` in Java plugin       | `PdfGeneratorPlugin.java`                     |

---

## What Never Changes

The following files are the module core. **Do not modify these** — they are shared across all projects that use this submodule:

| File                      | Reason                                                |
| ------------------------- | ----------------------------------------------------- |
| `IPdfDataProvider.cs`     | The contract — changing breaks all existing providers |
| `IPdfGeneratorService.cs` | The generator contract — used for mocking in tests    |
| `PdfDocumentData.cs`      | The data model — changing breaks JSON serialisation   |
| `PdfGeneratorService.cs`  | The bridge — any change affects all projects          |
| `PdfStorageService.cs`    | Filename logic — stable across all use cases          |
| `AndroidManifest.xml`     | Permissions config — changing may break file access   |
| `file_paths.xml`          | FileProvider paths — changing breaks URI sharing      |

If you need project-specific PDF layout logic, add it to your `IPdfDataProvider` implementation or to a project-specific subclass — never fork the core files.

---

## SOLID Principles Applied

| Principle                     | Implementation in this module                                                                                                                                    |
| ----------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **S — Single Responsibility** | `PdfGeneratorService` only bridges. `PdfStorageService` only handles filenames. `PdfDocumentData` only holds data. Each class has exactly one job.               |
| **O — Open / Closed**         | New data sources = new `IPdfDataProvider`. The core never changes. You extend by addition, not modification.                                                     |
| **L — Liskov Substitution**   | Any `IPdfDataProvider` is interchangeable: `MockChecklistDataProvider`, `ChecklistDataAdapter`, `InvoiceDataAdapter` — the generator treats them identically.    |
| **I — Interface Segregation** | `PdfGeneratorService` knows nothing about storage. `PdfStorageService` knows nothing about data. `IPdfDataProvider` knows nothing about how the PDF is drawn.    |
| **D — Dependency Inversion**  | `PdfGeneratorService` depends on `IPdfDataProvider` and `IPdfGeneratorService` (interfaces), never on concrete classes. Swap any part without touching the rest. |
