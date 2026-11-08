# FluoCode Reader

An ImageJ / Fiji plugin  for decoding FluoCodes from multichannel fluorescent images.

Each pair of channels uniquely defines one FluoCode.

It can decode up to *N*(*N*−1)/2 labels, where *N* is the number of channels.


## Installation

1. Download [`FluoCode_Reader-1.0.0.jar`](https://github.com/daichimori/FluoCode_Reader/releases/download/v1.0.0/FluoCode_Reader-1.0.0.jar).
2. Copy it into the `plugins` folder of Fiji. If an older version of the JAR is present, remove it first.
3. Restart Fiji.



## Menu

After installation, the plugin appear under **Plugins › FluoCode Reader**:

| Command | Purpose |
|---|---|
| **FluoCode Reader...** | Main tool.  Decodes from unmixed multichannel images into a decoded FluoCode image and also generates probability maps from them. |
| **Decode FluoCodes from Probability Map...** | Decodes an existing probability map into a decoded FluoCode image. Trying another threshold does not need the machine learning classification again.|
| **Count Decoded Pixels per Region...** | Counts decoded FluoCod pixels within each region of a segmentation image. |
| **Draw Region Boundaries on Decoded FluoCodes...** | Displays a decoded FluoCode image with the region boundaries generated from the registered brain atlas image|

## Requirements

- Fiji (or ImageJ 1.x), Java 8 or later

- A pixel classifier, which FluoCode Reader generate probability maps. The plugin does not train a classifier: you train one beforehand. Install one of the following tools.



### ilastik — for `.ilp` project files (recommended)

- **The ilastik application is required**, not only its Fiji plugin. You perform the labeling and training, and save the `.ilp` project file for FluoCode Reader.

- **The ilastik Fiji plugin is required** , and **it must be configured before use**. In Fiji, run **Plugins › ilastik › Configure ilastik executable location** and point it at the ilastik executable inside the directory where ilastik is installed , for example, `C:\Program Files\ilastik-1.4.0\ilastik.exe`.

>Until this path is set, prediction fails and FluoCode Reader cannot produce probability maps. Set it again whenever ilastik is moved, reinstalled or updated.

>Keep the `.ilp` project **closed in ilastik** while FluoCode Reader runs. The plugin cannot use a project file that the ilastik has open.

### Labkit — for Labkit classifier files

- Provides `Calculate Probability Map With Labkit`. Run it with the GPU option enabled.


---


## FluoCode Reader...

### Input
<img width="198" height="123" alt="image" src="https://github.com/user-attachments/assets/eb692547-7425-48c6-b0e4-7e971c85901b" />


A drop-down list lets you choose one of the following:

- **An open image** — every image currently open in Fiji is listed.
- **Select Image File...** — a single image file.
- **Select Folder...** — every file in the folder is processed.

Each image should be an **unmixed multichannel image**. 

>
> To process data at several z positions, save each z position as individual TIFF file, collect those files in one folder, and process them from **Select Folder...**. 

### Processing overview


1. **Single-channel prediction.** Each single-color channel is classified, giving one probability map per channel. Optionally, a residual unmixing leakage correction runs if linear unmixing leaves some leakage between spectrally close channels, so each channel has the brightest other channel *of its own channel group* × the leakage parameter subtracted. 
2. **Hue extraction.** For each channel pair (*a*, *b*), the hue-extracted image *I*<sub>ab</sub> is formed from the **raw** channels:
   `I_ab = max(0, min(a, b) − max(other channels) × k)`
   where *k* is the leakage parameter. 
3. **Probability map.** *I*<sub>ab</sub> is classified with the same classifier, giving the probability map *P*<sub>ab</sub>.
4. **Corrected probability map.** *P*<sub>ab\*</sub> is the per-pixel minimum of the two single-channel probabilities and *P*<sub>ab</sub>. This is the map that is saved and decoded.


| Option | Description |
|---|---|
| Save directory | Output folder (required). |
| Classifier File | `.ilp` files are run with ilastik; any other file is run with Labkit (required). |
| Leakage parameter k (0–1) | The *k* of the hue-extraction parameter above: the fraction of the brightest *other* channel without `min(a, b)`. A larger *k* means **stricter** threshold.|
| Subtract FRET-derived signals | Opens a table of rows (Channel A, Channel B, Channel C, FRET leakage parameter *x*). `min(A, B) × x` is subtracted from channel C, and everything downstream then uses the corrected channel. Channel C must differ from A and B. See [Subtraction of FRET-derived signals](#subtraction-of-fret-derived-signals) above. |
| Save probability map (P_ab*) | Saves *P*<sub>ab\*</sub> for every pair (8-bit, one slice per pair). On by default. |
| Save hue-extracted images (I_ab) | Saves *I*<sub>ab</sub> for every pair (16-bit). Off by default. |
| Save decoded FluoCode image | Saves the decoded FluoCode image (see below). On by default.|
| Decoding threshold (0.1–1) | Pixels whose highest *P*<sub>ab\*</sub> is below this value are labeled 0. The default value (0.2) is recommended. |
| Saturated Value (0–65535) | Pixels where any raw channel reaches this value are **taken out of decoding** and labeled 255 as an exception to classification since saturated pixels no longer tells its true intensity. |
| Correct residual unmixing leakage / Channel group 1–3 | Each field defines one group of channels that still leak into each other after linear unmixing, e.g. `1-3` or `4,5`. Before single-channel classification, each channel in a group has the brightest other channel of the **same group** × unmixing leakage parameter subtracted. Off by default.|
| Unmixing leakage parameter (0–1) | The subtraction factor of the residual unmixing leakage correction. Distinct from the leakage parameter *k* of hue extraction. The default value (0.2) is recommended. |

At least one output must be selected.

### Options
<img width="486" height="540" alt="image" src="https://github.com/user-attachments/assets/ef9c9915-b86e-4ede-a78c-90a4924d5b86" />

### Pair numbering

Pairs are numbered in the order (1,2), (1,3), …, (1,*N*), (2,3), …, (*N*−1,*N*). This number is the slice index in the probability map stack and the label value in the decoded FluoCode image.

### Output files

| File | Content |
|---|---|
| `Prob_<name>.tif` |  Probability map stack *P*<sub>ab\*</sub> (8-bit, 0–255) |
| `HueExtracted_<name>.tif` | Hue-extracted image stack *I*<sub>ab</sub> (16-bit) |
| `Decoded_<name>.tif` | Decoded FluoCode image, 8-bit: 0 = background, 1…*N*(*N*−1)/2 = FluoCode (pair number), 255 = saturated. A label LUT is applied. |

**Decode FluoCodes from Probability Map** writes its output under the same name, `Decoded_<name>.tif`, because it works on the same probability map by the same decoding rule. Re-decoding `Prob_<name>.tif` into the same folder therefore replaces the image written here. Use a separate save directory if you want to keep both.


#### Subtraction of FRET-derived signals

<img width="451" height="489" alt="image" src="https://github.com/user-attachments/assets/5759c293-3e8f-4df6-b50e-5fa3476af3d3" />

Ticking the option opens a table, and each row takes four values:

| Field | Meaning |
|---|---|
| Channel A, Channel B | the two channels of the FluoCode that undergoes FRET |
| Channel C | the channel its signal appears in; must differ from A and B |
| FRET leakage parameter *x* | the fraction of min(a, b) to subtract |

The corrected channels are used for the single-channel predictions of step 1, and both `min(a, b)` and `max(other channels)` in step 2. 

---

## Decode FluoCodes from Probability Map...

Decodes a probability map stack (for example, `Prob_<name>.tif` from FluoCode Reader) into a decoded FluoCode image. Useful for re-decoding with a different threshold without repeated classification tasks.

<img width="201" height="95" alt="image" src="https://github.com/user-attachments/assets/b35b2a92-4eab-4278-9bf8-60f7d6d1fca1" />

### Input

The same drop-down list as FluoCode Reader (an open image, an image file, or a folder).

- When **an open image** is selected, the result is also displayed.
- When **a folder** is selected, only files whose names start with `Prob_` are processed, so that other outputs of FluoCode Reader in the same folder are skipped. The number of skipped files is written to the Log.

### Options

| Option | Description |
|---|---|
| Save directory | Output folder (required). |
| Threshold (0–1) | Pixels whose maximum probability is below this value are labeled 0. The default value (0.2) is recommended. |
| Slices to exclude (optional) | Slices (1-based, comma-separated, e.g. `2,10`) to ignore during decoding, so that the FluoCodes they carry are left out of the result. Leave it empty — the default — to use every slice. Numbers that do not exist in the image are reported in the Log. |
| LUT file (optional) | LUT applied to the output. If empty, the same label palette as FluoCode Reader is used. |



### Output files

| File | Content |
|---|---|
| `Decoded_<name>.tif` | Decoded FluoCode image, in the same format as the one from FluoCode Reader. |

---


## Atlas files

**Count Decoded Pixels per Region** and **Draw Region Boundaries on Decoded FluoCodes** both need a region segmentation image that is aligned to the sample. Two files are provided [here](https://github.com/daichimori/FluoCode_Reader/releases/download/v1.0.0/annotationAtlas.zip).

**1. `annotationAtlas.tif` — atlas segmentation image (16-bit TIFF)**

A region segmentation image derived from the annotation volume of the Allen Mouse Brain Common Coordinate Framework version 3 (CCFv3; Wang et al., 2020). Each pixel value identifies one brain region. Values are stored as 16-bit integers (0–65535), and 0 denotes pixels outside the atlas.

For "Count Decoded Pixels per Region..." and "## Draw Region Boundaries on Decoded FluoCodes...", it must be registered to the sample image, for example by warping it with BigWarp in Fiji. Register the atlas to the fluorescence image, as it was before FluoCode Reader ran. A decoded FluoCode image holds nothing but label values, so the anatomical structures or landmarks are no longer visible and the alignment is difficult. 
Use **nearest-neighbor interpolation** during warping, so that pixel values remain valid region labels and are not blended into values that belong to other regions. After registration, it must have the same width, height and number of slices as the decoded FluoCode image (`Decoded_<name>.tif`) it is paired with.

**2. `value_name_.csv` — region list**

A two-column list mapping pixel values in `annotationAtlas.tif` to region names.

To use a modified list with the plugin, edit the CSV and rebuild the JAR (see [Building from source](#building-from-source)). 
When editing it, save as UTF-8 **without BOM** and **without a header line**, keep pixel values within 0–65535, and keep region names unique and free of commas.

---

## Count Decoded Pixels per Region...

Counts how many pixels carry each FluoCode label for each region.

### Usage

1. Open the region segmentation image and the decoded FluoCode image (`Decoded_<name>.tif`). Both must have the same size and number of slices. Multi-slice stacks are supported; counts are summed over all slices. Therefore, if you want to count decoded results per region as a volume like whole-brain mapping, you need to combine the resulting `Decoded_<name>.tif` files into one stack (**Image › Stacks › Images to Stack**). The segmentation image must already be registered to the sample — see [Atlas files](#atlas-files).
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
| Value 1 … Value *n* Pixel Count | Number of pixels in the region with each FluoCode, one column per label, where *n* is **Number of FluoCodes** |

Labels outside 1…*n* are not counted per value, but are included in the region total; this covers background (0) and saturated pixels (255). If any pixel of the selected regions carries a label above *n*, the Log reports how many there are and the highest label found, so that **Number of FluoCodes** can be corrected.

### Region names

The list of region names offered in the dialog. Only regions on that list can be counted. 
The same list is distributed with the atlas files as `value_name_.csv`.

---

## Draw Region Boundaries on Decoded FluoCodes...

Displays a copy of a decoded FluoCode image with the boundaries of all regions of a segmentation image, for visualization. 
Display only: neither input image is modified and nothing is saved.

### Usage

1. Open the atlas image and the decoded FluoCode image (`Decoded_<name>.tif`). Both must have the same size and number of slices.
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

[Example data](https://github.com/daichimori/FluoCode_Reader/releases/download/v1.0.0/ExampleData_FluoCodes.zip) for trying the plugin .

| File | Content |
|---|---|
| Example image | An unmixed multichannel image, so that FluoCode Reader can be tried without preparing data first. |
| Example classifier (`.ilp`) | The ilastik project trained for that image. Pass it to FluoCode Reader as the **Classifier File** to reproduce the example output. |

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.

## Citation

If you use this software, please cite the associated publication. Citation details will be added upon publication.
