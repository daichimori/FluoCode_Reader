# FluoCode Reader

An ImageJ / Fiji plugin suite for decoding multiplexed fluorescent barcodes (FluoCodes) from multichannel images and quantifying them per region.

Each FluoCode is defined by a **pair of channels**. For an image with *N* channels, all *N*(*N*−1)/2 channel pairs are evaluated, and each pair corresponds to one FluoCode label.

## Menu

After installation, the tools appear under **Plugins › FluoCode Reader**:

| Command | Purpose |
|---|---|
| **FluoCode Reader...** | Main tool. Generates FluoCode probability maps from unmixed multichannel images, and optionally decodes them into a label image. |
| **Decode FluoCodes from Probability Map...** | Decodes an existing probability map into a FluoCode label image, without re-running the classifiers. |
| **Count Decoded Pixels per Region...** | Counts decoded FluoCode pixels within each region of a segmentation image. |
| **Draw Region Boundaries on Decoded FluoCodes...** | Displays a decoded FluoCode label image with the region boundaries of a segmentation image drawn on it. |

Typical workflow: **FluoCode Reader** → (optionally) **Decode FluoCodes from Probability Map** → **Count Decoded Pixels per Region**.

**Draw Region Boundaries on Decoded FluoCodes** is an independent display tool; it can be run on a decoded label image at any point and is not part of the counting workflow.

## Requirements

- Fiji (or ImageJ 1.x), Java 8 or later
- One of the following, depending on the classifier file used in FluoCode Reader:
    - **ilastik** ImageJ plugin — for `.ilp` project files (`Run Pixel Classification Prediction`)
    - **Labkit** — for Labkit classifier files (`Calculate Probability Map With Labkit`; run with the GPU option enabled)

## Installation

1. Download `FluoCode_Reader-1.0.0.jar` from the [Releases](https://github.com/daichimori/FluoCode_Reader/releases) page.
2. Copy it into the `plugins` folder of Fiji. If an older version of the JAR is present, remove it first.
3. Restart Fiji.

---

## FluoCode Reader...

### Input

A drop-down list lets you choose one of the following:

- **An open image** — every image currently open in Fiji is listed. The image is processed as displayed and is not closed afterwards.
- **Select Image File...** — a single image file.
- **Select Folder...** — every file in the folder that Fiji can open is processed.

Each image should be an **unmixed multichannel image**. Only the first z-slice and time point are used.

### Processing overview

1. **Single-channel probability.** Each channel is classified on its own (optionally after unmixing-group subtraction, see below).
2. **Pair signal.** For each channel pair (*a*, *b*):
   `diff = max(0, min(a, b) − max(other channels) × Leak Tolerance)`
   with optional FRET leakage correction applied to `min(a, b)` beforehand.
3. **Pair probability.** `diff` is classified with the same classifier.
4. **Final probability.** For each pair, the per-pixel minimum of the two single-channel probabilities and the pair probability.

### Options

| Option | Description |
|---|---|
| Save directory | Output folder (required). |
| Classifier File | `.ilp` files are run with ilastik; any other file is run with Labkit (required). |
| Leak Tolerance (0–1) | Fraction of the brightest *other* channel subtracted from each pair signal. Higher values make pair calling stricter. |
| Additional unmixing for FRET | Opens a table of rows (Channel A, Channel B, Channel C, Leakage Level *x*). For any pair made of C and either A or B, `min(A, B) × x` is subtracted from the pair signal. Channel C must differ from A and B. |
| Save Probability Map stack | Saves the final probability of every pair (8-bit, one slice per pair). On by default. |
| Save MinDiff stack | Saves the `diff` image of every pair (16-bit). |
| Save FinalLabel image | Saves the decoded label image (see below). |
| FinalLabel threshold (0.1–1) | Pixels whose highest pair probability is below this value are labeled 0. |
| Saturated Value (0–65535) | Pixels where any raw channel reaches this value are labeled 255 in FinalLabel. Intended for 16-bit raw images. |
| Use Unmixing Groups / Group 1–3 | Each field defines one group of channels, e.g. `1,2,3` or `4-6`. Before single-channel classification, each channel in a group has the brightest other channel of the same group × Unmix Strength subtracted. Pair signals are not affected. |
| Unmix Strength (0–1) | Subtraction factor for unmixing groups. |

At least one output must be selected.

### Pair numbering

Pairs are numbered in the order (1,2), (1,3), …, (1,*N*), (2,3), …, (*N*−1,*N*). This number is the slice index in the probability map stack and the label value in FinalLabel.

### Output files

| File | Content |
|---|---|
| `Prob_<name>.tif` | Final probability map stack (8-bit, 0–255) |
| `MinDiffStack_<name>.tif` | Pair difference stack (16-bit) |
| `FinalLabel_<name>.tif` | 8-bit label image: 0 = background, 1…*N*(*N*−1)/2 = FluoCode (pair number), 255 = saturated. A label LUT is applied. |

All parameters are written to the Log window, followed by per-channel and per-pair progress, the processing time of each image, and the total processing time.

---

## Decode FluoCodes from Probability Map...

Decodes a probability map stack (for example, `Prob_<name>.tif` from FluoCode Reader) into a label image. Useful for re-decoding with a different threshold or with some FluoCodes excluded, without re-running the classifiers.

### Input

The same drop-down list as FluoCode Reader (an open image, an image file, or a folder).

- When **an open image** is selected, the result is also displayed.
- When **a folder** is selected, only files whose names start with `Prob_` are processed, so that other outputs of FluoCode Reader in the same folder are skipped. The number of skipped files is written to the Log.

### Options

| Option | Description |
|---|---|
| Save directory | Output folder (required). |
| Threshold (0–1) | Pixels whose maximum probability is below this value are labeled 0. |
| Slices to exclude | Slices (1-based, comma-separated, e.g. `3,5`) that are ignored during decoding. Numbers that do not exist in the image are reported in the Log. |
| LUT file (optional) | LUT applied to the output. If empty, the same label palette as FluoCode Reader is used. |

### Decoding rule

Each pixel is assigned the slice number in which it has the highest value (the first slice wins on ties). The decoding rule and the threshold are the same as for FinalLabel in FluoCode Reader, so the same probability map and threshold give the same labels.

- Slices in which every pixel is 128 (probability 0.5 everywhere, i.e. no classifier output) are skipped automatically and reported in the Log.
- 8-bit and 16-bit maps are scaled to 0–1 before thresholding; 32-bit maps are assumed to contain probabilities in 0–1.
- Saturated pixels cannot be identified from a probability map, so label 255 is not used.
- Stacks with more than 254 slices are not supported.

### Output files

| File | Content |
|---|---|
| `Decoded_<name>.tif` | 8-bit label image: 0 = background, 1…*n* = slice number of the maximum. A leading `Prob_` in the input name is removed (e.g. `Prob_sample.tif` → `Decoded_sample.tif`). |

---

## Count Decoded Pixels per Region...

Counts, for each selected region, how many pixels carry each FluoCode label.

### Usage

1. Open the region segmentation image and the decoded FluoCode label image (e.g. `FinalLabel_<name>.tif` or `Decoded_<name>.tif`). Both must have the same size and number of slices. Multi-slice stacks are supported; counts are summed over all slices.
2. Run the command (an image must be open).
3. In the dialog:
    - **Select Segmentation Result:** the region segmentation image.
    - **Select Raw Image:** the decoded FluoCode label image.
4. Select one or more regions from the list (Ctrl/Shift-click for multiple).

Each slice is read only once, regardless of how many regions are selected.

### Results table

| Column | Content |
|---|---|
| Region | Region name |
| Pixel Counts | Total number of pixels in the region |
| Value 1 … Value 21 Pixel Count | Number of pixels in the region with each FluoCode label (1–21) |

Labels outside 1–21 (background, saturated) are not counted per value, but are included in the region total. The current version counts labels 1–21, which corresponds to 7-channel data (21 channel pairs).

### Region list (`value_name_.csv`)

Region names are read from `value_name_.csv`, embedded at the root of the JAR. Each line is `pixel value,region name`:


- The number is the pixel value of the region in the segmentation image (0–65535).
- Save as UTF-8 **without BOM**, and **without a header line**. A non-numeric line stops reading at that point.
- Lines whose region name contains a comma are ignored.
- Region names must be unique.
- To change the list, edit the CSV and rebuild the JAR.

---

## Draw Region Boundaries on Decoded FluoCodes...

Displays a copy of a decoded FluoCode label image with the boundaries of all regions of a segmentation image drawn on it, for figures and visual inspection. Display only: neither input image is modified and nothing is saved.

### Usage

1. Open the region segmentation image and the decoded FluoCode label image (e.g. `FinalLabel_<name>.tif` or `Decoded_<name>.tif`). Both must have the same size and number of slices.
2. Run the command.
3. In the dialog:
    - **Allen Atlas:** the region segmentation image.
    - **Decoded FluoCodes:** the decoded FluoCode label image (must be 8-bit).
    - **Thicken boundaries by 1 px:** grows the boundary lines by one pixel in every direction (8-connected).

The result is shown as a new stack named `Boundaries_<title>`.

### How boundaries are drawn

- Boundaries are detected slice by slice with Find Edges (Sobel) on the segmentation image; every pixel with an edge value of 1 or more is treated as a boundary. Lines are about 2 pixels wide, or about 4 with **Thicken boundaries by 1 px**.
- Boundary pixels are set to **250**. Value 255 is not used because it marks saturated pixels in FinalLabel.
- Pixels outside the atlas (segmentation value 0) are set to **251** and shown black, so that they stay distinguishable from saturated pixels.
- The label image's LUT is kept, with only entries 250 (gray, RGB 100, 100, 100) and 251 (black) changed.

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

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.

## Citation

If you use this software, please cite the associated publication. Citation details will be added upon publication.