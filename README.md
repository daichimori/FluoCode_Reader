# FluoCode Reader

An ImageJ / Fiji plugin suite for decoding multiplexed fluorescent barcodes (FluoCodes) from multichannel images and quantifying them per region.

Each FluoCode is defined by a **pair of channels**. For an image with *N* channels, all *N*(*N*−1)/2 channel pairs are evaluated, and each pair corresponds to one FluoCode label.

## Menu

After installation, the tools appear under **Plugins › FluoCode Reader**:

| Command | Purpose |
|---|---|
| **FluoCode Reader...** | Main tool. Generates corrected FluoCode probability maps from unmixed multichannel images, and optionally decodes them into a decoded FluoCode image. |
| **Decode FluoCodes from Probability Map...** | Decodes an existing probability map into a decoded FluoCode image, without re-running the classifiers. |
| **Count Decoded Pixels per Region...** | Counts decoded FluoCode pixels within each region of a segmentation image. |
| **Draw Region Boundaries on Decoded FluoCodes...** | Displays a decoded FluoCode image with the region boundaries of a segmentation image drawn on it. |

Typical workflow: **FluoCode Reader** → **Count Decoded Pixels per Region**.

The other two commands sit beside this workflow rather than inside it:

- **Decode FluoCodes from Probability Map** — it re-decodes an already saved probability map at a different decoding threshold, or with some FluoCodes left out, so that trying another threshold does not mean running the classifiers again from the start.
- **Draw Region Boundaries on Decoded FluoCodes** — it shows where the region boundaries fall on a decoded FluoCode image, for figures and for judging the atlas registration by eye. It can be run on a decoded FluoCode image at any time; counting is not the only way to look at the data.

## Requirements

- Fiji (or ImageJ 1.x), Java 8 or later
- A pixel classifier, which FluoCode Reader calls to turn images into probability maps. The plugin does not train a classifier itself: you train one beforehand and pass the resulting file to it as the **Classifier File**. Install one of the following, depending on which kind of classifier file you use.

### ilastik — for `.ilp` project files (recommended)

- **The ilastik application is required**, not only its Fiji plugin. You do the labeling there, and that labeling is what produces the `.ilp` project file for FluoCode Reader.
- **The ilastik Fiji plugin is required** (it provides `Run Pixel Classification Prediction`), and **it must be configured before use**. In Fiji, run **Plugins › ilastik › Configure ilastik executable location** and point it at the ilastik executable inside the directory where ilastik is installed — on Windows, for example, `C:\Program Files\ilastik-1.4.0\ilastik.exe`. Until this path is set, and set to the real installation, prediction fails and FluoCode Reader cannot produce probability maps. Set it once per Fiji installation, and again whenever ilastik is moved, reinstalled or updated.
- Keep the `.ilp` project **closed in ilastik** while FluoCode Reader runs. The plugin cannot use a project file that the ilastik application has open.

### Labkit — for Labkit classifier files

- Provides `Calculate Probability Map With Labkit`. Run it with the GPU option enabled.

## Installation

1. Download `FluoCode_Reader-1.0.0.jar` from the [Releases](https://github.com/daichimori/FluoCode_Reader/releases) page.
2. Copy it into the `plugins` folder of Fiji. If an older version of the JAR is present, remove it first.
3. Restart Fiji.

---


## FluoCode Reader...

### Input
<img width="198" height="123" alt="image" src="https://github.com/user-attachments/assets/eb692547-7425-48c6-b0e4-7e971c85901b" />


A drop-down list lets you choose one of the following:

- **An open image** — every image currently open in Fiji is listed. The image is processed as displayed and is not closed afterwards.
- **Select Image File...** — a single image file.
- **Select Folder...** — every file in the folder that Fiji can open is processed.

Each image should be an **unmixed multichannel image**. The number of channels *N* is read from the image itself, and it is what determines the *N*(*N*−1)/2 channel pairs that are evaluated.


> **Only the first z-slice and the first time point are processed.** If an image holds several z-slices or several frames, the remaining ones are ignored without a warning.
>
> To process data at several z positions, save each z position as its own TIFF file, collect those files in one folder, and process them from **Select Folder...** — or one at a time from **Select Image File...**. Each file then gets its own set of outputs. If those results are later to be counted per region as a volume, combine the resulting `Decoded_<name>.tif` files into one stack (**Image › Stacks › Images to Stack**), so that the stack matches the slices of the registered atlas.

### Processing overview

If any FRET rows are configured, the channels are corrected first and every step below then works on the corrected channels — see [Subtraction of FRET-derived signals](#subtraction-of-fret-derived-signals).

1. **Single-channel prediction.** Each channel is classified on its own, giving one probability map per channel. Optionally, a residual unmixing leakage correction runs first: linear unmixing leaves some signal leaking between spectrally close channels, so each channel has the brightest other channel *of its own channel group* × the unmixing leakage parameter subtracted before it is classified. It is off by default, and it changes nothing except these single-channel predictions. If a channel's probability comes back uniformly 0.5 at every pixel — what ilastik returns for an image holding only one class, see the [Notes](#notes) under **Decode FluoCodes from Probability Map** below — it is replaced by zero. Because *P*<sub>ab\*</sub> is a per-pixel minimum that includes the single-channel probabilities, that channel then contributes nothing to any pair it belongs to.
2. **Hue extraction.** For each channel pair (*a*, *b*), the hue-extracted image *I*<sub>ab</sub> is formed from the **raw** channels:
   `I_ab = max(0, min(a, b) − max(other channels) × k)`
   where *k* is the leakage parameter. Hue extraction reads the channels themselves, not the single-channel predictions, so the residual unmixing leakage correction of step 1 never reaches *I*<sub>ab</sub>.
3. **Probability map.** *I*<sub>ab</sub> is classified with the same classifier, giving the probability map *P*<sub>ab</sub>.
4. **Corrected probability map.** *P*<sub>ab\*</sub> is the per-pixel minimum of the two single-channel probabilities and *P*<sub>ab</sub>. This is the map that is saved and decoded.

#### Subtraction of FRET-derived signals

When a FluoCode undergoes FRET, part of its signal appears in a channel that was not labeled for it, and hue extraction then calls pairs that are not really there. **Subtract FRET-derived signals** removes those pairs. The correction is the one described in Figure S5 of the paper; it is not linear unmixing.

Ticking the option opens a table, and each row takes four values:

| Field | Meaning |
|---|---|
| Channel A, Channel B | the two channels of the FluoCode that undergoes FRET |
| Channel C | the channel its signal appears in; must differ from A and B |
| FRET leakage parameter *x* | the fraction of the *A*+*B* signal to subtract (0–1) |

`min(A, B) × x` is the leak the row accounts for. Each row's leak is added onto its own channel *C*, so a channel named by several rows collects the leak of all of them, and that channel becomes `max(0, C − leak)`. Every `min(A, B)` is taken from the uncorrected image, and the correction is applied **once per channel**, not again per pair.

The corrected channels are then what the rest of the run uses: the single-channel predictions of step 1, and both `min(a, b)` and `max(other channels)` in step 2. Channels no row names are left untouched, and the image you started from is never modified. Give one row per FluoCode that shows FRET; a row naming a channel the image does not have is skipped, with a warning in the Log.

### Options
<img width="486" height="540" alt="image" src="https://github.com/user-attachments/assets/ef9c9915-b86e-4ede-a78c-90a4924d5b86" />


| Option | Description |
|---|---|
| Save directory | Output folder (required). |
| Classifier File | `.ilp` files are run with ilastik; any other file is run with Labkit (required). |
| Leakage parameter k (0–1) | The *k* of the hue-extraction formula above: the fraction of the brightest *other* channel subtracted from `min(a, b)`. A larger *k* removes more signal, so pair calling becomes **stricter**.|
| Subtract FRET-derived signals | Opens a table of rows (Channel A, Channel B, Channel C, FRET leakage parameter *x*). `min(A, B) × x` is subtracted from channel C, and everything downstream then uses the corrected channel. Channel C must differ from A and B. See [Subtraction of FRET-derived signals](#subtraction-of-fret-derived-signals) above. |
| Save corrected probability map (P_ab*) | Saves *P*<sub>ab\*</sub> for every pair (8-bit, one slice per pair). On by default. |
| Save hue-extracted images (I_ab) | Saves *I*<sub>ab</sub> for every pair (16-bit). Off by default. |
| Save decoded FluoCode image | Saves the decoded FluoCode image (see below). On by default.|
| Decoding threshold (0.1–1) | Pixels whose highest *P*<sub>ab\*</sub> is below this value are labeled 0. The default value (0.2) is recommended. |
| Saturated Value (0–65535) | Pixels where any raw channel reaches this value are **taken out of decoding** and labeled 255 instead of a FluoCode. A saturated channel no longer reports its true intensity, so the balance between channels — which is what a FluoCode is — cannot be recovered for such a pixel. Label 255 is therefore an exception to classification: these pixels are set aside instead of being given a FluoCode that may be wrong, and **Count Decoded Pixels per Region** does not count them under any FluoCode label. Intended for 16-bit raw images. |
| Correct residual unmixing leakage / Channel group 1–3 | Each field defines one group of channels that still leak into each other after linear unmixing, e.g. `1-3` or `4,5`. Before single-channel classification, each channel in a group has the brightest other channel of the **same group** × unmixing leakage parameter subtracted. |
| Unmixing leakage parameter (0–1) | The subtraction factor of the residual unmixing leakage correction. Distinct from the leakage parameter *k* of hue extraction. The default value (0.2) is recommended. |

At least one output must be selected.

### Pair numbering

Pairs are numbered in the order (1,2), (1,3), …, (1,*N*), (2,3), …, (*N*−1,*N*). This number is the slice index in the probability map stack and the label value in the decoded FluoCode image.

### Output files

| File | Content |
|---|---|
| `Prob_<name>.tif` | Corrected probability map stack *P*<sub>ab\*</sub> (8-bit, 0–255) |
| `HueExtracted_<name>.tif` | Hue-extracted image stack *I*<sub>ab</sub> (16-bit) |
| `Decoded_<name>.tif` | Decoded FluoCode image, 8-bit: 0 = background, 1…*N*(*N*−1)/2 = FluoCode (pair number), 255 = saturated. A label LUT is applied. |

**Decode FluoCodes from Probability Map** writes its output under the same name, `Decoded_<name>.tif`, because it works on the same probability map by the same decoding rule. Re-decoding `Prob_<name>.tif` into the same folder therefore replaces the image written here. Use a separate save directory if you want to keep both.

---

## Decode FluoCodes from Probability Map...

Decodes a probability map stack (for example, `Prob_<name>.tif` from FluoCode Reader) into a decoded FluoCode image. Useful for re-decoding with a different threshold or with some FluoCodes excluded, without re-running the classifiers.

### Input

The same drop-down list as FluoCode Reader (an open image, an image file, or a folder).

- When **an open image** is selected, the result is also displayed.
- When **a folder** is selected, only files whose names start with `Prob_` are processed, so that other outputs of FluoCode Reader in the same folder are skipped. The number of skipped files is written to the Log.

### Options

| Option | Description |
|---|---|
| Save directory | Output folder (required). |
| Threshold (0–1) | Pixels whose maximum probability is below this value are labeled 0. The default value (0.2) is recommended. |
| Slices to exclude (optional) | Slices (1-based, comma-separated, e.g. `3,5`) to ignore during decoding, so that the FluoCodes they carry are left out of the result. Leave it empty — the default — to use every slice. Numbers that do not exist in the image are reported in the Log. |
| LUT file (optional) | LUT applied to the output. If empty, the same label palette as FluoCode Reader is used. |

### Notes

- Slices in which every pixel is 128 are skipped automatically and reported in the Log. This corrects for a bug in ilastik: when an image holds only one class — in practice when it is all background, with no signal anywhere — ilastik does not report it as background. It returns a probability of 0.5 for both classes at every pixel, so the signal-class map, which is the one the plugin reads, comes back uniformly 0.5 (128 in 8-bit) instead of 0. A flat 0.5 would still beat the genuinely low probabilities of the other slices and win the per-pixel maximum, claiming the image for one FluoCode. Skipping these slices prevents that.
- Saturated pixels cannot be identified only from a probability map, so label 255 is not used in "Decode FluoCodes from Probability Map...".


### Output files

| File | Content |
|---|---|
| `Decoded_<name>.tif` | Decoded FluoCode image, in the same format as the one from FluoCode Reader. |

---


## Atlas files

**Count Decoded Pixels per Region** and **Draw Region Boundaries on Decoded FluoCodes** both need a region segmentation image that is already aligned to the sample. Two files are provided for this, deposited on Zenodo:

- **Zenodo record:** _URL to be added_
- **DOI:** _to be added_

**1. `annotationAtlas.tif` — atlas segmentation image (16-bit TIFF)**

A region segmentation image derived from the annotation volume of the Allen Mouse Brain Common Coordinate Framework version 3 (CCFv3; Wang et al., 2020). Each pixel value identifies one brain region. Values are stored as 16-bit integers (0–65535), and 0 denotes pixels outside the atlas.

Before use with the FluoCode Reader plugin, it must be registered to the sample image, for example by warping it with BigWarp in Fiji. Use **nearest-neighbor interpolation** during warping, so that pixel values remain valid region labels and are not blended into values that belong to other regions. After registration, it must have the same width, height and number of slices as the decoded FluoCode image (`Decoded_<name>.tif`) it is paired with.

**2. `value_name_.csv` — region list**

A two-column list mapping pixel values in `annotationAtlas.tif` to region names. Each line is in the form `pixel value,region name`. Region names are unique and contain no commas. This file is identical to the `value_name_.csv` embedded at the root of `FluoCode_Reader-1.0.0.jar`, which is the list the plugin actually reads; it is provided here so that the region definitions can be inspected without unpacking the JAR.

To use a modified list with the plugin, edit the CSV and rebuild the JAR (see [Building from source](#building-from-source)). When editing it, save as UTF-8 **without BOM** and **without a header line**, keep pixel values within 0–65535, and keep region names unique and free of commas — a line whose name contains a comma is ignored, and a non-numeric line stops reading at that point.


>Register the atlas to the fluorescence image, as it was before FluoCode Reader ran. A decoded FluoCode image holds nothing but label values, so the anatomical structures that landmarks are placed on are no longer visible and the alignment is difficult. 

---

## Count Decoded Pixels per Region...

Counts how many pixels carry each FluoCode label for each region.

### Usage

1. Open the region segmentation image and the decoded FluoCode image (`Decoded_<name>.tif`). Both must have the same size and number of slices. Multi-slice stacks are supported; counts are summed over all slices. The segmentation image must already be registered to the sample — see [Atlas files](#atlas-files).
2. Run the command (an image must be open).
3. In the dialog:
    - **Allen Atlas:** the region segmentation image.
    - **Decoded FluoCodes:** the decoded FluoCode image.
    - **Number of FluoCodes:** how many FluoCode labels to count, from 1 to 254. The default, 21, is the number of channel pairs in 7-channel data; for *N* channels set it to *N*(*N*−1)/2. The value is remembered until Fiji is closed.
4. Select one or more regions from the list (Ctrl/Shift-click for multiple).


### Results table

| Column | Content |
|---|---|
| Region | Region name |
| Pixel Counts | Total number of pixels in the region |
| Value 1 … Value *n* Pixel Count | Number of pixels in the region with each FluoCode label, one column per label, where *n* is **Number of FluoCodes** |

Labels outside 1…*n* are not counted per value, but are included in the region total; this covers background (0) and saturated pixels (255). If any pixel of the selected regions carries a label above *n*, the Log reports how many there are and the highest label found, so that **Number of FluoCodes** can be corrected.

### Region names

The region names offered in the dialog and written to the Results table come from the region list inside the JAR, which also fixes which pixel value of the segmentation image belongs to which region. Only regions on that list can be counted. The same list is distributed with the atlas files as `value_name_.csv`; see [Atlas files](#atlas-files) above to inspect it or to use a list of your own.

---

## Draw Region Boundaries on Decoded FluoCodes...

Displays a copy of a decoded FluoCode image with the boundaries of all regions of a segmentation image drawn on it, for figures and visual inspection. Display only: neither input image is modified and nothing is saved.

### Usage

1. Open the region segmentation image and the decoded FluoCode image (`Decoded_<name>.tif`). Both must have the same size and number of slices.
2. Run the command.
3. In the dialog:
    - **Allen Atlas:** the region segmentation image.
    - **Decoded FluoCodes:** the decoded FluoCode image (must be 8-bit).
    - **Thicken boundaries by 1 px:** grows the boundary lines by one pixel in every direction (8-connected).

The result is shown as a new stack named `Boundaries_<title>`.

### How boundaries are drawn

- Boundaries are detected slice by slice with Find Edges (Sobel) on the segmentation image; every pixel with an edge value of 1 or more is treated as a boundary. Lines are about 2 pixels wide, or about 4 with **Thicken boundaries by 1 px**.
- Boundary pixels are set to **250**. Value 255 is not used because it marks saturated pixels in decoded FluoCode images from FluoCode Reader.
- Pixels outside the atlas (segmentation value 0) are set to **251** and shown black, so that they stay distinguishable from saturated pixels.
- The decoded FluoCode image's LUT is kept, with only entries 250 (gray, RGB 100, 100, 100) and 251 (black) changed.

---

## Building from source

Requires Maven and Java 8 or later.

```
mvn clean package
```

The JAR is written to `target/FluoCode_Reader-1.0.0.jar`.

```
src/main/java/
├── FluoCode_Reader.java          FluoCode Reader...
├── FluoCode_Identifier.java      Decode FluoCodes from Probability Map...
├── FluoCode_Region_Counter.java  Count Decoded Pixels per Region...
└── FluoCode_Region_Outliner.java Draw Region Boundaries on Decoded FluoCodes...
src/main/resources/
├── plugins.config                menu entries
└── value_name_.csv               region list
```

Menu entries are defined in `plugins.config`:

```
Plugins>FluoCode Reader, "FluoCode Reader...", FluoCode_Reader
Plugins>FluoCode Reader, "-"
Plugins>FluoCode Reader, "Decode FluoCodes from Probability Map...", FluoCode_Identifier
Plugins>FluoCode Reader, "Count Decoded Pixels per Region...", FluoCode_Region_Counter
Plugins>FluoCode Reader, "Draw Region Boundaries on Decoded FluoCodes...", FluoCode_Region_Outliner
```

## Example data

Example data for trying the plugin is to be deposited on Zenodo.

- **Zenodo record:** _URL to be added_

| File | Content |
|---|---|
| Example image | An unmixed multichannel image, so that FluoCode Reader can be tried without preparing data first. |
| Example classifier (`.ilp`) | The ilastik project trained for that image. Pass it to FluoCode Reader as the **Classifier File** to reproduce the example output. |

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.

## Citation

If you use this software, please cite the associated publication. Citation details will be added upon publication.
