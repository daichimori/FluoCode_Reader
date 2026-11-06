import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.io.DirectoryChooser;
import ij.io.FileSaver;
import ij.io.OpenDialog;
import ij.plugin.ChannelSplitter;
import ij.plugin.PlugIn;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.LUT;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;

public class FluoCode_Reader implements PlugIn {

    /* ---------- output flags (defaults) ---------- */
    private static boolean saveProbMap = true; // default ON
    private static boolean saveHueExtracted = false; // default OFF
    private static boolean saveDecoded = true; // default ON

    private ArrayList<int[]> fretRows = new ArrayList<>();
    private ArrayList<Double> fretLeakage = new ArrayList<>();
    private ArrayList<int[]> channelGroups = new ArrayList<>();
    private ArrayList<String> groupParseErrors = new ArrayList<>();

    private static final String VERSION = "1.0.0";
    private static double decodeThresh = 0.2; // 0.1–1.0, UI
    private static double leakageK = 0.2; // 0–1, UI  (leakage parameter k, pair loop only)
    private static double unmixLeakage = 0.2; // 0–1, UI  (channel groups only)
    private static double saturatedValue = 65535; // 0–65535,
    private static boolean correctUnmixLeakage = false;

    //Log output in the first loop
    private StringBuilder runLog = new StringBuilder();
    private boolean headerFlushed = false;

    private ImagePlus openInput = null;

    private void logRun(String msg) {
        runLog.append(msg).append("\n");
    }

    private void flushRunLog() {
        if (runLog.length() > 0) {
            IJ.log(runLog.toString());
            runLog.setLength(0);
        }
    }

    private void flushRunLogOnce() {
        if (!headerFlushed) {
            flushRunLog();
            headerFlushed = true;
        }
    }

    @Override
    public void run(String arg) {
        long startTime = System.currentTimeMillis();
        headerFlushed = false;
        runLog.setLength(0);

        // --- ① Select Image or Folder
        String imagePath = chooseImageOrFolder();
        if (imagePath == null)
            return;
        File target = new File(imagePath);

        // --- ② All option in one dialog ---
        GenericDialog gd = new GenericDialog("Setup All Options");
        gd.addDirectoryField("Save directory", "");
        gd.addFileField("Classifier File", "");
        gd.addNumericField("Leakage parameter k (0–1)", leakageK, 2);
        gd.addCheckbox("Subtract FRET-derived signals", false);
        gd.addMessage("-------- Output Options --------");
        gd.addCheckbox("Save corrected probability map (P_ab*)", saveProbMap);
        gd.addCheckbox("Save hue-extracted images (I_ab)", saveHueExtracted);
        gd.addCheckbox("Save decoded FluoCode image", saveDecoded);
        gd.addNumericField("Decoding threshold (0.1–1)", decodeThresh, 2);
        gd.addNumericField("Saturated Value (0–65535)", saturatedValue, 0);

        // --- Residual unmixing leakage option (per single channel) ---
        gd.addCheckbox("Correct residual unmixing leakage", false);
        gd.addMessage("Channel groups that leak into each other (e.g., 1,2,3 / 4-6)");
        gd.addStringField("Channel group 1", "");
        gd.addStringField("Channel group 2", "");
        gd.addStringField("Channel group 3", "");
        gd.addNumericField("Unmixing leakage parameter (0–1)", unmixLeakage, 2);

        gd.showDialog();
        if (gd.wasCanceled())
            return;

        String saveDir = gd.getNextString();
        String classifierPath = gd.getNextString();
        leakageK = Math.max(0.0, Math.min(1.0, gd.getNextNumber()));
        boolean doFretSubtract = gd.getNextBoolean();
        saveProbMap = gd.getNextBoolean();
        saveHueExtracted = gd.getNextBoolean();
        saveDecoded = gd.getNextBoolean();
        decodeThresh = Math.max(0.1, Math.min(1.0, gd.getNextNumber()));
        saturatedValue = (int) Math.max(0, Math.min(65535, gd.getNextNumber()));

        correctUnmixLeakage = gd.getNextBoolean();
        String g1 = gd.getNextString();
        String g2 = gd.getNextString();
        String g3 = gd.getNextString();
        unmixLeakage = Math.max(0.0, Math.min(1.0, gd.getNextNumber()));

        if (correctUnmixLeakage) {
            channelGroups.clear();
            groupParseErrors.clear();

            parseGroupString(g1, "Channel group 1");
            parseGroupString(g2, "Channel group 2");
            parseGroupString(g3, "Channel group 3");

            if (!groupParseErrors.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append("Invalid input in channel groups:\n\n");
                for (String err : groupParseErrors) {
                    sb.append(" - ").append(err).append("\n");
                }
                IJ.error("Channel group error", sb.toString());
                return; //
            }
        }

        if (saveDir.isEmpty() || classifierPath.isEmpty()) {
            IJ.error("Save directory and Classifier File are required.");
            return;
        }
        if (!saveProbMap && !saveHueExtracted && !saveDecoded) {
            IJ.error("Select at least one output.");
            return;
        }

        // --- Parameter Log---
        logRun("========== FluoCode Reader v" + VERSION + " ==========");
        if (openInput != null) {
            logRun(" Input image            : " + openInput.getTitle());
        } else if (target.isDirectory()) {
            logRun(" Input folder           : " + imagePath);
        } else {
            logRun(" Input file             : " + imagePath);
        }

        logRun(" Save directory         : " + saveDir);
        logRun(" Classifier file        : " + classifierPath);
        logRun(" Leakage parameter k    : " + leakageK);
        logRun(" Unmixing leakage       : " + unmixLeakage);

        // Output: True
        StringBuilder outSb = new StringBuilder();
        if (saveProbMap || saveHueExtracted || saveDecoded) {
            outSb.append(" Output                 :");
            boolean first = true;
            if (saveProbMap) {
                outSb.append(first ? " CorrectedProbMap" : ", CorrectedProbMap");
                first = false;
            }
            if (saveHueExtracted) {
                outSb.append(first ? " HueExtracted" : ", HueExtracted");
                first = false;
            }
            if (saveDecoded) {
                outSb.append(first ? " DecodedFluoCodes" : ", DecodedFluoCodes");
                first = false;
            }
        } else {
            outSb.append(" Output                 : None");
        }
        logRun(outSb.toString());

        logRun(" Decoding threshold     : " + decodeThresh);
        logRun(" Saturated Value (16bit): " + saturatedValue);

        // Channel groups : [Ch1, Ch2]  or None
        if (correctUnmixLeakage && !channelGroups.isEmpty()) {
            logRun(" Channel groups         :");
            for (int gi = 0; gi < channelGroups.size(); gi++) {
                int[] g = channelGroups.get(gi);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < g.length; i++) {
                    if (i > 0)
                        sb.append(", ");
                    sb.append("Ch").append(g[i] + 1); // 1 (start) & "Ch"
                }
                logRun("  [" + sb.toString() + "]");
            }
        } else {
            logRun(" Channel groups         : None");
        }

        // --- ③ Setup window for subtraction of FRET-derived signals ---
        if (doFretSubtract) {
            setupFretRows();
        }

        // FRET : [...] style or None
        if (doFretSubtract && !fretRows.isEmpty()) {
            for (int i = 0; i < fretRows.size(); i++) {
                int[] pair = fretRows.get(i);
                double leak = fretLeakage.get(i);
                int a = pair[0] + 1;
                int b = pair[1] + 1;
                int c = pair[2] + 1;
                logRun(" FRET" + (i + 1) + ": [" + a + "," + b + ": to " + c
                        + "] leakage parameter = " + leak);
            }
        } else {
            logRun(" FRET-derived signals   : None");
        }

        // --- ④ processing loop ---
        if (openInput != null) {
            String base = openInput.getTitle().replaceFirst("\\.[^.]+$", "");
            processAndSaveImage(openInput, base, saveDir, classifierPath);
            // Do not close: the image was opened by the user
        } else if (target.isDirectory()) {
            File[] files = target.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (!file.isFile())
                        continue;
                    ImagePlus imp = IJ.openImage(file.getPath());
                    if (imp == null)
                        continue;
                    String base = file.getName().replaceFirst("\\.[^.]+$", "");
                    processAndSaveImage(imp, base, saveDir, classifierPath);
                    imp.close();
                }
            }
        } else {
            ImagePlus imp = IJ.openImage(imagePath);
            if (imp != null) {
                String base = target.getName().replaceFirst("\\.[^.]+$", "");
                processAndSaveImage(imp, base, saveDir, classifierPath);
                imp.close();
            }
        }

        // If it cannot perform Pair loop processing
        if (!headerFlushed) {
            flushRunLogOnce();
        }
        // --- ⑤ processing time log---
        IJ.log("Total processing time: "
                + formatElapsed(System.currentTimeMillis() - startTime));
    }

    /** Input selection: an open image, an image file, or a folder */
    private static final String CHOICE_FILE = "Select Image File...";
    private static final String CHOICE_FOLDER = "Select Folder...";

    private String chooseImageOrFolder() {
        openInput = null;

        // Open images first, then File / Folder
        int[] ids = WindowManager.getIDList();
        ArrayList<String> items = new ArrayList<>();
        if (ids != null) {
            for (int id : ids) {
                items.add(WindowManager.getImage(id).getTitle());
            }
        }
        int nOpen = items.size();
        items.add(CHOICE_FILE);
        items.add(CHOICE_FOLDER);

        ImagePlus current = WindowManager.getCurrentImage();
        String defaultItem = (current != null) ? current.getTitle() : CHOICE_FILE;

        GenericDialog gd = new GenericDialog("Select Input");
        gd.addChoice("Input:", items.toArray(new String[0]), defaultItem);
        gd.showDialog();
        if (gd.wasCanceled())
            return null;

        int idx = gd.getNextChoiceIndex();
        if (idx < nOpen) {
            openInput = WindowManager.getImage(ids[idx]);
            return (openInput != null) ? openInput.getTitle() : null;
        } else if (idx == nOpen) {
            OpenDialog od = new OpenDialog("Select an Image", "");
            return od.getPath();
        } else {
            DirectoryChooser dc = new DirectoryChooser("Select a Folder");
            return dc.getDirectory();
        }
    }
    /* ---------- Main ---------- */
    private void processAndSaveImage(ImagePlus imp, String fileName,
                                     String saveDir, String classifierPath) {
        long imageStart = System.currentTimeMillis();
        int w = imp.getWidth(), h = imp.getHeight(), chNum = imp.getNChannels();
        int n = w * h;

        /* ---------- Raw image  float[] cash（read-only） ---------- */
        float[][] raw = new float[chNum][];
        for (int c = 0; c < chNum; c++) {
            ImageProcessor ip = imp.getStack()
                    .getProcessor(imp.getStackIndex(c + 1, 1, 1));
            raw[c] = (float[]) ip.toFloat(0, null).getPixels();
        }

        /* ---------- FRET reference image, min(raw[a], raw[b]) ---------- */
        float[][] fretRef = new float[fretRows.size()][];
        for (int idx = 0; idx < fretRows.size(); idx++) {
            int[] pr = fretRows.get(idx);
            if (pr[0] >= chNum || pr[1] >= chNum || pr[2] >= chNum) {
                IJ.log("  [WARN] FRET" + (idx + 1) + " references a channel beyond "
                        + chNum + " — this row is skipped for " + fileName);
                continue; // fretRef[idx]  keeps  null
            }
            float[] ra = raw[pr[0]], rb = raw[pr[1]];
            float[] ref = new float[n];
            for (int j = 0; j < n; j++) {
                ref[j] = Math.min(ra[j], rb[j]);
            }
            fretRef[idx] = ref;
        }

        /* --- Output stacks --- */
        ImageStack probStack = new ImageStack(w, h); // corrected probability map P_ab* (8-bit)
        ImageStack hueStack = new ImageStack(w, h); // hue-extracted images I_ab (16-bit)

        /* ---------- singleProb per channel ---------- */
        ByteProcessor[] singleProb = new ByteProcessor[chNum];
        for (int c = 0; c < chNum; c++) {

            // duplicate for the classifier
            ImagePlus slice = new ImagePlus(
                    "slice_c" + (c + 1),
                    imp.getStack().getProcessor(imp.getStackIndex(c + 1, 1, 1)).duplicate());

            /* --- Residual unmixing leakage correction (per single-color image) --- */
            if (correctUnmixLeakage) {
                int[] targetGroup = null;
                for (int[] g : channelGroups) {
                    for (int cc : g) {
                        if (cc == c) {
                            targetGroup = g;
                            break;
                        }
                    }
                    if (targetGroup != null)
                        break;
                }

                if (targetGroup != null && targetGroup.length > 1) {
                    float[] mx = new float[n];
                    for (int cc : targetGroup) {
                        if (cc == c || cc >= chNum)
                            continue; //
                        float[] po = raw[cc];
                        for (int j = 0; j < n; j++) {
                            if (po[j] > mx[j])
                                mx[j] = po[j];
                        }
                    }

                    float ul = (float) unmixLeakage;
                    ImageProcessor spi = slice.getProcessor();
                    for (int y = 0, j = 0; y < h; y++) {
                        for (int x = 0; x < w; x++, j++) {
                            float v = spi.getf(x, y) - mx[j] * ul;
                            if (v < 0)
                                v = 0;
                            spi.setf(x, y, v);
                        }
                    }
                }
            }

            slice.show();

            IJ.log("Processing Channel " + (c + 1) + " (slice_c" + (c + 1)
                    + ") using classifier: " + classifierPath);

            if (classifierPath.endsWith(".ilp")) {
                IJ.run("Run Pixel Classification Prediction",
                        "projectfilename=[" + classifierPath + "] "
                                + "input=[slice_c" + (c + 1) + "] "
                                + "pixelclassificationtype=[Probabilities]");
            } else {
                IJ.run("Calculate Probability Map With Labkit",
                        "segmenter_file=[" + classifierPath + "] use_gpu=true");
            }

            ImagePlus out = WindowManager.getCurrentImage();
            ImageProcessor p;
            if (out.getNChannels() > 1) { // Ilastik 2-ch BG/FG
                ImagePlus[] sp = ChannelSplitter.split(out);
                p = sp[1].getProcessor();
                for (ImagePlus t : sp)
                    t.close();
            } else {
                p = out.getProcessor();
            }
            p.multiply(255.0); // 0.0~1.0 > 0.0~255.0
            singleProb[c] = (ByteProcessor) p.convertToByte(false);
            checkAndReplaceIfUniform(singleProb[c]);
            out.close();
            slice.close();
        }

        /* ---------- pair loop (min → diff → seg) ---------- */
        int totalPairs = chNum * (chNum - 1) / 2;
        int count = 0;

        float[] mn = new float[n];
        float[] mx = new float[n];
        float lk = (float) leakageK;

        for (int k = 0; k < chNum; k++) {
            for (int l = 0; l < chNum; l++) {
                if (k >= l)
                    continue;
                count++;

                // ★ Output RunLog
                flushRunLogOnce();

                IJ.log("  [Pair] " + count + "/" + totalPairs +
                        " (c" + (k + 1) + "," + (l + 1) + ") File: " + fileName);

                /* --- min(a,b) --- */
                float[] pk = raw[k], pl = raw[l];
                for (int j = 0; j < n; j++) {
                    mn[j] = Math.min(pk[j], pl[j]);
                }

                /* --- subtract FRET-derived signal if this pair matches a row --- */
                for (int idx = 0; idx < fretRows.size(); idx++) {
                    float[] ref = fretRef[idx];
                    if (ref == null)
                        continue; //
                    int[] pair = fretRows.get(idx);
                    int a = pair[0], b = pair[1], c = pair[2];
                    if ((k == c && (l == a || l == b)) || (l == c && (k == a || k == b))) {
                        float lf = (float) (double) fretLeakage.get(idx);
                        for (int j = 0; j < n; j++) {
                            mn[j] -= ref[j] * lf;
                        }
                    }
                }

                /* --- max(others) --- */
                java.util.Arrays.fill(mx, 0f);
                for (int c = 0; c < chNum; c++) {
                    if (c == k || c == l)
                        continue;
                    float[] po = raw[c];
                    for (int j = 0; j < n; j++) {
                        if (po[j] > mx[j])
                            mx[j] = po[j];
                    }
                }

                /* --- hue extraction: I_ab = max(0, min(a,b) - k * max(others)) --- */
                float[] df = new float[n];
                for (int j = 0; j < n; j++) {
                    float v = mn[j] - mx[j] * lk;
                    df[j] = v > 0f ? v : 0f;
                }
                FloatProcessor diffFp = new FloatProcessor(w, h, df, null);

                /* --- save hue-extracted slice I_ab (16-bit) --- */
                hueStack.addSlice("I_" + (k + 1) + "_" + (l + 1),
                        diffFp.convertToShort(false));

                /* --- segmentation by classifier --- */
                ImagePlus diffImg = new ImagePlus("diff", diffFp);
                diffImg.show();
                if (classifierPath.endsWith(".ilp")) {
                    IJ.run("Run Pixel Classification Prediction",
                            "projectfilename=[" + classifierPath
                                    + "] input=[diff] pixelclassificationtype=[Probabilities]");
                } else {
                    IJ.run("Calculate Probability Map With Labkit",
                            "segmenter_file=[" + classifierPath + "] use_gpu=true");
                }

                ImagePlus seg = WindowManager.getCurrentImage();
                ByteProcessor segBp;
                if (seg.getNChannels() > 1) {
                    ImagePlus[] sp = ChannelSplitter.split(seg);
                    ImageProcessor tmpP = sp[1].getProcessor();
                    tmpP.multiply(255.0); // x255 Probability map
                    segBp = (ByteProcessor) tmpP.convertToByte(false);
                    for (ImagePlus t : sp)
                        t.close();
                } else {
                    ImageProcessor tmpP = seg.getProcessor();
                    tmpP.multiply(255.0); // x255 Probability map
                    segBp = (ByteProcessor) tmpP.convertToByte(false);
                }

                /* --- final = min(singleProb[k], singleProb[l], segBp) --- */
                byte[] pkb = (byte[]) singleProb[k].getPixels();
                byte[] plb = (byte[]) singleProb[l].getPixels();
                byte[] sgb = (byte[]) segBp.getPixels();
                byte[] fin = new byte[n];
                for (int j = 0; j < n; j++) {
                    int v = Math.min(pkb[j] & 0xFF, plb[j] & 0xFF);
                    v = Math.min(v, sgb[j] & 0xFF);
                    fin[j] = (byte) v;
                }
                ByteProcessor finalBp = new ByteProcessor(w, h, fin, null);

                probStack.addSlice("Pstar_" + fileName + "_pair" + count, finalBp);
                seg.close();
                diffImg.close();
            }
        }

        /* ---------- save section (flags bifurcation) ---------- */
        if (saveProbMap) {
            IJ.saveAsTiff(new ImagePlus("ProbMap", probStack),
                    saveDir + File.separator + "Prob_" + fileName + ".tif");
        }
        if (saveHueExtracted) {
            new FileSaver(new ImagePlus("HueExtracted", hueStack))
                    .saveAsTiffStack(saveDir + File.separator + "HueExtracted_" + fileName + ".tif");
        }
        if (saveDecoded) {
            makeDecodedImage(probStack, imp, saveDir, fileName);
        }
        IJ.log("  Finished " + fileName + " in "
                + formatElapsed(System.currentTimeMillis() - imageStart));
    }

    /* ---------- helpers ---------- */

    private void checkAndReplaceIfUniform(ByteProcessor bp) {
        byte[] d = (byte[]) bp.getPixels();
        for (byte b : d)
            if ((b & 0xFF) != 128)
                return;
        java.util.Arrays.fill(d, (byte) 0);
    }
    /** Formats elapsed time, e.g. "1 h 5 min 12 s" (hours only when needed) */
    private static String formatElapsed(long ms) {
        long s = ms / 1000;
        long h = s / 3600, m = (s / 60) % 60, sec = s % 60;
        if (h > 0)
            return h + " h " + m + " min " + sec + " s";
        return m + " min " + sec + " s";
    }

    /* ========== makeDecodedImage() ========== */
    private void makeDecodedImage(ImageStack probStack, ImagePlus rawImp,
                                  String saveDir, String base) {

        int w = probStack.getWidth(), h = probStack.getHeight();
        int nPairs = probStack.getSize(); // total label number
        int nCh = rawImp.getNChannels(); // Ch number of raw images

        // Saturation test
        boolean[] isSat = new boolean[w * h];
        for (int c = 1; c <= nCh; c++) {
            ImageProcessor ip = rawImp.getStack()
                    .getProcessor(rawImp.getStackIndex(c, 1, 1));
            for (int y = 0, idx = 0; y < h; y++) {
                for (int x = 0; x < w; x++, idx++) {
                    if (!isSat[idx]) {
                        int v16 = ip.getPixel(x, y) & 0xFFFF; // 16-bit
                        if (v16 >= saturatedValue)
                            isSat[idx] = true;
                    }
                }
            }
        }

        byte[] maxProb = new byte[w * h];
        byte[] label = new byte[w * h];

        for (int z = 1; z <= nPairs; z++) {
            byte[] px = (byte[]) probStack.getProcessor(z).getPixels();
            for (int i = 0; i < px.length; i++) {
                if (isSat[i])
                    continue;
                int v = px[i] & 0xFF;
                if (v > (maxProb[i] & 0xFF)) {
                    maxProb[i] = px[i];
                    label[i] = (byte) z;
                }
            }
        }

        int thr = (int) Math.round(decodeThresh * 255.0);
        for (int i = 0; i < label.length; i++) {
            if (isSat[i]) {
                label[i] = (byte) 255;
            } else if ((maxProb[i] & 0xFF) < thr) {
                label[i] = 0;
            }
        }

        ByteProcessor lblBP = new ByteProcessor(w, h, label, null);
        ImagePlus out = new ImagePlus("Decoded_" + base, lblBP);
        out.getProcessor().setLut(makeDynamicLabelLUT(nPairs));

        new FileSaver(out)
                .saveAsTiff(saveDir + File.separator + "Decoded_" + base + ".tif");
    }

    /* ---------- Setup for subtraction of FRET-derived signals ---------- */
    private void setupFretRows() {
        JDialog dialog = new JDialog((Frame) null,
                "Subtraction of FRET-derived signals", true);

        DefaultTableModel model = new DefaultTableModel(
                new Object[] { "Channel A", "Channel B", "Channel C", "FRET leakage parameter (x)" }, 0) {
            @Override
            public boolean isCellEditable(int row, int col) {
                return true;
            }
        };

        model.addRow(new Object[] { 1, 4, 3, 0.0 });

        JTable table = new JTable(model);

        JButton add = new JButton("Add Row");
        add.addActionListener(e -> model.addRow(new Object[] { 1, 4, 3, 0.0 }));

        JButton del = new JButton("Delete Selected Row");
        del.addActionListener(e -> {
            int r = table.getSelectedRow();
            if (r >= 0) {
                model.removeRow(r);
            } else {
                JOptionPane.showMessageDialog(dialog,
                        "Select a row to delete.", "",
                        JOptionPane.WARNING_MESSAGE);
            }
        });

        JButton ok = new JButton("OK");
        ok.addActionListener(e -> {
            ArrayList<int[]> tmpRows = new ArrayList<>();
            ArrayList<Double> tmpLeaks = new ArrayList<>();
            for (int i = 0; i < model.getRowCount(); i++) {
                try {
                    int a = Integer.parseInt(model.getValueAt(i, 0).toString().trim()) - 1;
                    int b = Integer.parseInt(model.getValueAt(i, 1).toString().trim()) - 1;
                    int c = Integer.parseInt(model.getValueAt(i, 2).toString().trim()) - 1;
                    double x = Double.parseDouble(model.getValueAt(i, 3).toString().trim());
                    if (a < 0 || b < 0 || c < 0) {
                        JOptionPane.showMessageDialog(dialog,
                                "Row " + (i + 1) + ": Channel numbers must be 1 or greater.",
                                "Input error", JOptionPane.WARNING_MESSAGE);
                        return; //
                    }
                    if (c == a || c == b) {
                        JOptionPane.showMessageDialog(dialog,
                                "Row " + (i + 1) + ": Channel C must differ from Channel A and Channel B.\n"
                                        + "Otherwise, the correction would be applied to the A-B pair itself.",
                                "Input error", JOptionPane.WARNING_MESSAGE);
                        return;
                    }
                    tmpRows.add(new int[] { a, b, c });
                    tmpLeaks.add(x);
                } catch (NumberFormatException ex) {
                    JOptionPane.showMessageDialog(dialog,
                            "Row " + (i + 1) + ": One or more cells are not valid numbers.",
                            "Input error", JOptionPane.WARNING_MESSAGE);
                    return; // Dont destroy the existing one
                }
            }
            // It is reflected after the all codes run
            fretRows.clear();
            fretLeakage.clear();
            fretRows.addAll(tmpRows);
            fretLeakage.addAll(tmpLeaks);
            dialog.dispose();
        });

        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> {
            fretRows.clear();
            fretLeakage.clear();
            dialog.dispose();
        });

        JPanel btnPanel = new JPanel();
        btnPanel.add(add);
        btnPanel.add(del);
        btnPanel.add(ok);
        btnPanel.add(cancel);

        dialog.getContentPane().add(new JScrollPane(table), BorderLayout.CENTER);
        dialog.getContentPane().add(btnPanel, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true);
    }

    /* ---------- Channel group string parser (strict) ---------- */
    private void parseGroupString(String s, String label) {
        if (s == null)
            return;
        s = s.trim();
        if (s.isEmpty())
            return;

        ArrayList<Integer> list = new ArrayList<>();
        String original = s;

        for (String token : s.split(",")) {
            token = token.trim();
            if (token.isEmpty())
                continue;

            try {
                int from, to;
                if (token.contains("-")) {
                    String[] lr = token.split("-");
                    if (lr.length != 2) {
                        groupParseErrors.add(label + ": '" + token
                                + "' is not a valid range; use the form a-b (input: '" + original + "')");

                        continue;
                    }
                    from = Integer.parseInt(lr[0].trim());
                    to = Integer.parseInt(lr[1].trim());
                } else {
                    from = to = Integer.parseInt(token);
                }

                if (from <= 0 || to <= 0) {
                    groupParseErrors.add(label + ": '" + token
                            + "' contains a channel number less than 1 (input: '" + original + "')");
                    continue;
                }

                if (to < from) {
                    int tmp = from;
                    from = to;
                    to = tmp;
                }

                for (int ch = from; ch <= to; ch++) {
                    list.add(ch - 1);
                }

            } catch (NumberFormatException e) {
                groupParseErrors.add(label + ": '" + token
                        + "' is not an integer (input: '" + original + "')");
            }
        }

        if (list.isEmpty()) {
            groupParseErrors.add(label + ": no valid channel numbers (input: '" + original + "')");
            return;
        }

        int[] arr = new int[list.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = list.get(i);
        }
        channelGroups.add(arr);
    }

    private LUT makeDynamicLabelLUT(int n) {
        n = Math.min(254, Math.max(1, n));

        byte[] r = new byte[256];
        byte[] g = new byte[256];
        byte[] b = new byte[256];

        r[0] = g[0] = b[0] = (byte) 255;
        r[255] = g[255] = b[255] = 0;

        double base_s = 0.8, base_v = 0.85, ds = 0.05, dv = 0.05;
        double startHue = 0.75;
        int start = (int) Math.round(startHue * n) % n;

        for (int i = 0; i < n; i++) {
            double hh = (double) i / n;
            double s = base_s + ds * ((i % 2 == 0) ? 1 : -1);
            double v = base_v + dv * (((i + 1) % 2 == 0) ? 1 : -1);

            int rgb = Color.HSBtoRGB((float) hh, (float) s, (float) v);
            int R = (rgb >> 16) & 0xFF;
            int G = (rgb >> 8) & 0xFF;
            int B = rgb & 0xFF;

            int idx = (start - i) % n;
            if (idx < 0)
                idx += n;
            int labelIdx = 1 + idx;

            r[labelIdx] = (byte) R;
            g[labelIdx] = (byte) G;
            b[labelIdx] = (byte) B;
        }

        return new LUT(r, g, b);
    }
}