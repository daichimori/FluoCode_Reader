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
import java.util.HashSet;

public class FluoCode_Reader implements PlugIn {

    /* ---------- output flags (defaults) ---------- */
    private static boolean saveProbMap = true; // default ON
    private static boolean saveMinDiffStack = false; // default OFF
    private static boolean saveFinalLabel = true; // default OFF

    private ArrayList<int[]> channelPairs = new ArrayList<>();
    private ArrayList<Double> leakageLevels = new ArrayList<>();
    private ArrayList<int[]> unmixGroups = new ArrayList<>();
    private ArrayList<String> unmixParseErrors = new ArrayList<>();

    private static final String VERSION = "1.0.0";
    private static double labelThresh = 0.2; // 0.1–1.0, UI
    private static double hueStrictness = 0.2; // 0〜1, UI  (pair loop only)
    private static double unmixStrength = 0.2; // 0〜1, UI  (unmix group only)
    private static double saturatedValue = 65535; // 0–65535,
    private static boolean useUnmixGroups = false;

    private String specialClassifier = "";
    private HashSet<Integer> specialSet = new HashSet<>();

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
        gd.addNumericField("Leak Tolerance (0–1)", hueStrictness, 2);
        gd.addCheckbox("Additional unmixing for FRET", false);
        gd.addMessage("-------- Output Options --------");
        gd.addCheckbox("Save Probability Map stack", saveProbMap);
        gd.addCheckbox("Save MinDiff stack", saveMinDiffStack);
        gd.addCheckbox("Save FinalLabel image", saveFinalLabel);
        gd.addNumericField("FinalLabel threshold (0.1–1)", labelThresh, 2);
        gd.addNumericField("Saturated Value (0–65535)", saturatedValue, 0);

        // --- Unmixing groups option ---
        gd.addCheckbox("Use Unmixing Groups", false);
        gd.addMessage("Unmixing Groups (e.g., 1,2,3 / 4-6)");
        gd.addStringField("Group 1", "");
        gd.addStringField("Group 2", "");
        gd.addStringField("Group 3", "");
        gd.addNumericField("Unmix Strength (0–1)", unmixStrength, 2);

        gd.addMessage("---- Special Classifier For High-BG Channels ----");
        gd.addFileField("Special Classifier File (optional)", "");
        gd.addStringField("Channels using special classifier (e.g., 3,5)", "");

        gd.showDialog();
        if (gd.wasCanceled())
            return;

        String saveDir = gd.getNextString();
        String classifierPath = gd.getNextString();
        hueStrictness = Math.max(0.0, Math.min(1.0, gd.getNextNumber()));
        boolean doUnmix = gd.getNextBoolean();
        saveProbMap = gd.getNextBoolean();
        saveMinDiffStack = gd.getNextBoolean();
        saveFinalLabel = gd.getNextBoolean();
        labelThresh = Math.max(0.1, Math.min(1.0, gd.getNextNumber()));
        saturatedValue = (int) Math.max(0, Math.min(65535, gd.getNextNumber()));

        useUnmixGroups = gd.getNextBoolean();
        String g1 = gd.getNextString();
        String g2 = gd.getNextString();
        String g3 = gd.getNextString();
        unmixStrength = Math.max(0.0, Math.min(1.0, gd.getNextNumber()));

        specialClassifier = gd.getNextString();
        String specialChannels = gd.getNextString();
        specialSet = parseChannelList(specialChannels);

        if (specialClassifier != null && !specialClassifier.isEmpty()) {
            logRun(" Special Classifier     : " + specialClassifier);
            logRun(" Special Channels       : " + specialChannels);
        } else {
            logRun(" Special Classifier     : None");
        }

        if (useUnmixGroups) {
            unmixGroups.clear();
            unmixParseErrors.clear();

            parseGroupString(g1, "Group 1");
            parseGroupString(g2, "Group 2");
            parseGroupString(g3, "Group 3");

            if (!unmixParseErrors.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append("Invalid input in Unmixing Groups:\n\n");
                for (String err : unmixParseErrors) {
                    sb.append(" - ").append(err).append("\n");
                }
                IJ.error("Unmixing groups error", sb.toString());
                return; //
            }
        }

        if (saveDir.isEmpty() || classifierPath.isEmpty()) {
            IJ.error("Save directory and Classifier File are required.");
            return;
        }
        if (!saveProbMap && !saveMinDiffStack && !saveFinalLabel) {
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
        logRun(" Leak Tolerance (pair)  : " + hueStrictness);
        logRun(" Unmix Strength (group) : " + unmixStrength);

        // Output: True
        StringBuilder outSb = new StringBuilder();
        if (saveProbMap || saveMinDiffStack || saveFinalLabel) {
            outSb.append(" Output                :");
            boolean first = true;
            if (saveProbMap) {
                outSb.append(first ? " ProbMap" : ", ProbMap");
                first = false;
            }
            if (saveMinDiffStack) {
                outSb.append(first ? " MinDiffStack" : ", MinDiffStack");
                first = false;
            }
            if (saveFinalLabel) {
                outSb.append(first ? " FinalLabel" : ", FinalLabel");
                first = false;
            }
        } else {
            outSb.append(" Output                : None");
        }
        logRun(outSb.toString());

        logRun(" FinalLabel threshold   : " + labelThresh);
        logRun(" Saturated Value (16bit): " + saturatedValue);

        // Unmixing Groups : [Ch1, Ch2]  or None
        if (useUnmixGroups && !unmixGroups.isEmpty()) {
            logRun(" Unmixing Groups        :");
            for (int gi = 0; gi < unmixGroups.size(); gi++) {
                int[] g = unmixGroups.get(gi);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < g.length; i++) {
                    if (i > 0)
                        sb.append(", ");
                    sb.append("Ch").append(g[i] + 1); // 1 (start) & "Ch"
                }
                logRun("  [" + sb.toString() + "]");
            }
        } else {
            logRun(" Unmixing Groups        : None");
        }

        // --- ③  unmix group setting window（FRET pair） ---
        if (doUnmix) {
            setupChannelPairs();
        }

        // FRET : [...] style or None
        if (doUnmix && !channelPairs.isEmpty()) {
            for (int i = 0; i < channelPairs.size(); i++) {
                int[] pair = channelPairs.get(i);
                double leak = leakageLevels.get(i);
                int a = pair[0] + 1;
                int b = pair[1] + 1;
                int c = pair[2] + 1;
                logRun(" FRET" + (i + 1) + ": [" + a + "," + b + ": to " + c + "] leak =" + leak);
            }
        } else {
            logRun(" FRET                    : None");
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
    private static final String CHOICE_FILE = "Image file...";
    private static final String CHOICE_FOLDER = "Folder...";

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

        /* ---------- FRET pair reference, make min(raw[a], raw[b]) ---------- */
        float[][] fretRef = new float[channelPairs.size()][];
        for (int idx = 0; idx < channelPairs.size(); idx++) {
            int[] pr = channelPairs.get(idx);
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
        ImageStack probStack = new ImageStack(w, h); // Probability Map (8-bit)
        ImageStack minDiffStack = new ImageStack(w, h); // MinDiff (16-bit)

        /* ---------- singleProb per channel ---------- */
        ByteProcessor[] singleProb = new ByteProcessor[chNum];
        for (int c = 0; c < chNum; c++) {

            // duplicate for the classifier
            ImagePlus slice = new ImagePlus(
                    "slice_c" + (c + 1),
                    imp.getStack().getProcessor(imp.getStackIndex(c + 1, 1, 1)).duplicate());

            /* --- Unmixing group subtraction (per single-color image) --- */
            if (useUnmixGroups) {
                int[] targetGroup = null;
                for (int[] g : unmixGroups) {
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

                    float us = (float) unmixStrength;
                    ImageProcessor spi = slice.getProcessor();
                    for (int y = 0, j = 0; y < h; y++) {
                        for (int x = 0; x < w; x++, j++) {
                            float v = spi.getf(x, y) - mx[j] * us;
                            if (v < 0)
                                v = 0;
                            spi.setf(x, y, v);
                        }
                    }
                }
            }

            slice.show();

            /* ======== SPECIAL CLASSIFIER SWITCH ======== */

            String localClassifier = classifierPath; // default
            if (specialClassifier != null && !specialClassifier.isEmpty()) {
                if (specialSet.contains(c + 1)) { // since c=0→Ch1,  +1
                    localClassifier = specialClassifier;
                }
            }
            IJ.log("Processing Channel " + (c + 1) + " (slice_c" + (c + 1)
                    + ") using classifier: " + localClassifier);

            if (localClassifier.endsWith(".ilp")) {
                IJ.run("Run Pixel Classification Prediction",
                        "projectfilename=[" + localClassifier + "] "
                                + "input=[slice_c" + (c + 1) + "] "
                                + "pixelclassificationtype=[Probabilities]");
            } else {
                IJ.run("Calculate Probability Map With Labkit",
                        "segmenter_file=[" + localClassifier + "] use_gpu=true");
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
        float hs = (float) hueStrictness;

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

                /* --- leakage correction if match --- */
                for (int idx = 0; idx < channelPairs.size(); idx++) {
                    float[] ref = fretRef[idx];
                    if (ref == null)
                        continue; //
                    int[] pair = channelPairs.get(idx);
                    int a = pair[0], b = pair[1], c = pair[2];
                    if ((k == c && (l == a || l == b)) || (l == c && (k == a || k == b))) {
                        float lf = (float) (double) leakageLevels.get(idx);
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

                /* --- diff = min - max*hueStrictness --- */
                float[] df = new float[n];
                for (int j = 0; j < n; j++) {
                    float v = mn[j] - mx[j] * hs;
                    df[j] = v > 0f ? v : 0f;
                }
                FloatProcessor diffFp = new FloatProcessor(w, h, df, null);

                /* --- save MinDiff slice (16-bit) --- */
                minDiffStack.addSlice("MinDiff_" + (k + 1) + "_" + (l + 1),
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

                probStack.addSlice("P_" + fileName + "_pair" + count, finalBp);
                seg.close();
                diffImg.close();
            }
        }

        /* ---------- save section (flags bifurcation) ---------- */
        if (saveProbMap) {
            IJ.saveAsTiff(new ImagePlus("ProbMap", probStack),
                    saveDir + File.separator + "Prob_" + fileName + ".tif");
        }
        if (saveMinDiffStack) {
            new FileSaver(new ImagePlus("MinDiffStack", minDiffStack))
                    .saveAsTiffStack(saveDir + File.separator + "MinDiffStack_" + fileName + ".tif");
        }
        if (saveFinalLabel) {
            makeFinalLabel(probStack, imp, saveDir, fileName);
        }
        IJ.log("  Finished " + fileName + " in "
                + formatElapsed(System.currentTimeMillis() - imageStart));
    }

    /* ---------- helpers ---------- */

    private HashSet<Integer> parseChannelList(String s) {
        HashSet<Integer> set = new HashSet<>();
        if (s == null)
            return set;
        for (String t : s.split(",")) {
            t = t.trim();
            if (t.isEmpty())
                continue;
            try {
                int ch = Integer.parseInt(t);
                if (ch > 0)
                    set.add(ch);
            } catch (Exception ignored) {
            }
        }
        return set;
    }

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

    /* ========== makeFinalLabel() ========== */
    private void makeFinalLabel(ImageStack probStack, ImagePlus rawImp,
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

        int thr = (int) Math.round(labelThresh * 255.0);
        for (int i = 0; i < label.length; i++) {
            if (isSat[i]) {
                label[i] = (byte) 255;
            } else if ((maxProb[i] & 0xFF) < thr) {
                label[i] = 0;
            }
        }

        ByteProcessor lblBP = new ByteProcessor(w, h, label, null);
        ImagePlus out = new ImagePlus("FinalLabel_" + base, lblBP);
        out.getProcessor().setLut(makeDynamicLabelLUT(nPairs));

        new FileSaver(out)
                .saveAsTiff(saveDir + File.separator + "FinalLabel_" + base + ".tif");
    }

    /* ---------- Channel-pair & leakage setup ---------- */
    private void setupChannelPairs() {
        JDialog dialog = new JDialog((Frame) null,
                "Channel Pairs and Leakage Level", true);

        DefaultTableModel model = new DefaultTableModel(
                new Object[] { "Channel A", "Channel B", "Channel C", "Leakage Level (x)" }, 0) {
            @Override
            public boolean isCellEditable(int row, int col) {
                return true;
            }
        };

        model.addRow(new Object[] { 1, 4, 3, 0.0 });

        JTable table = new JTable(model);

        JButton add = new JButton("Add Pair");
        add.addActionListener(e -> model.addRow(new Object[] { 1, 4, 3, 0.0 }));

        JButton del = new JButton("Delete Selected Pair");
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
            ArrayList<int[]> tmpPairs = new ArrayList<>();
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
                    tmpPairs.add(new int[] { a, b, c });
                    tmpLeaks.add(x);
                } catch (NumberFormatException ex) {
                    JOptionPane.showMessageDialog(dialog,
                            "Row " + (i + 1) + ": One or more cells are not valid numbers.",
                            "Input error", JOptionPane.WARNING_MESSAGE);
                    return; // Dont destroy the existing one
                }
            }
            // It is reflected after the all codes run
            channelPairs.clear();
            leakageLevels.clear();
            channelPairs.addAll(tmpPairs);
            leakageLevels.addAll(tmpLeaks);
            dialog.dispose();
        });

        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> {
            channelPairs.clear();
            leakageLevels.clear();
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

    /* ---------- Unmixing group string parser (strict) ---------- */
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
                        unmixParseErrors.add(label + ": '" + token
                                + "' is not a valid range; use the form a-b (input: '" + original + "')");

                        continue;
                    }
                    from = Integer.parseInt(lr[0].trim());
                    to = Integer.parseInt(lr[1].trim());
                } else {
                    from = to = Integer.parseInt(token);
                }

                if (from <= 0 || to <= 0) {
                    unmixParseErrors.add(label + ": '" + token
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
                unmixParseErrors.add(label + ": '" + token
                        + "' is not an integer (input: '" + original + "')");
            }
        }

        if (list.isEmpty()) {
            unmixParseErrors.add(label + ": no valid channel numbers (input: '" + original + "')");
            return;
        }

        int[] arr = new int[list.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = list.get(i);
        }
        unmixGroups.add(arr);
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