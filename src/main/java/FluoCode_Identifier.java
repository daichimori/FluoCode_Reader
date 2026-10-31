import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.io.DirectoryChooser;
import ij.io.FileSaver;
import ij.io.OpenDialog;
import ij.plugin.PlugIn;
import ij.plugin.LutLoader;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import ij.process.LUT;

import java.awt.Color;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;

/**
 * Decode FluoCodes from Probability Map
 *   - Writes, for each pixel, the slice number at which the stack reaches its maximum
 *   - Slices can be excluded (1-based, comma-separated)
 *   - Pixels whose maximum is below a 0-1 threshold are labeled 0
 *   - Applies an optional LUT file (otherwise a dynamic HSV label palette)
 *
 * ver. 1.1.1
 */
public class FluoCode_Identifier implements PlugIn {

    private static final String VERSION = "1.1.1";
    private static double minThresh = 0.2;   // 0–1

    private static final String CHOICE_FILE = "Select Image File...";
    private static final String CHOICE_FOLDER = "Select Folder...";

    // In folder mode, only probability maps saved by FluoCode Reader are processed
    private static final String PROB_PREFIX = "Prob_";

    // Set when an already open image is chosen as input
    private ImagePlus openInput = null;

    @Override
    public void run(String arg) {
        long startTime = System.currentTimeMillis();

        // --- ① Select input ---
        String inputPath = chooseImageOrFolder();
        if (inputPath == null)
            return;
        File target = new File(inputPath);

        // --- ② Options ---
        GenericDialog gd = new GenericDialog("Decode FluoCodes");
        gd.addDirectoryField("Save directory", "");
        gd.addNumericField("Threshold (0–1)", minThresh, 2);
        gd.addStringField("Slices to exclude (e.g., 3,5)", "");
        gd.addFileField("LUT file (optional)", "");
        gd.showDialog();
        if (gd.wasCanceled())
            return;

        String saveDir = gd.getNextString().trim();
        minThresh = Math.max(0.0, Math.min(1.0, gd.getNextNumber()));
        String skipStr = gd.getNextString().trim();
        String lutPath = gd.getNextString().trim();

        if (saveDir.isEmpty()) {
            IJ.error("Save directory is required.");
            return;
        }

        // Read the LUT file once; stop here if it cannot be read
        LUT userLut = null;
        if (!lutPath.isEmpty()) {
            if (new File(lutPath).isFile())
                userLut = LutLoader.openLut(lutPath);
            if (userLut == null) {
                IJ.error("Could not read the LUT file:\n" + lutPath);
                return;
            }
        }
        HashSet<Integer> skipSet = parseSkipSet(skipStr);

        // --- Parameter log ---
        IJ.log("========== FluoCode Identifier v" + VERSION + " ==========");
        if (openInput != null) {
            IJ.log(" Input image            : " + openInput.getTitle());
        } else if (target.isDirectory()) {
            IJ.log(" Input folder           : " + inputPath + "  (" + PROB_PREFIX + "* files only)");
        } else {
            IJ.log(" Input file             : " + inputPath);
        }
        IJ.log(" Save directory         : " + saveDir);
        IJ.log(" Threshold              : " + minThresh);
        IJ.log(" Excluded slices        : " + (skipSet.isEmpty() ? "None" : skipStr));
        IJ.log(" LUT file               : " + (lutPath.isEmpty() ? "None (auto palette)" : lutPath));

        // --- ③ Processing loop ---
        if (openInput != null) {
            handleOneImage(openInput, stripExt(openInput.getTitle()), saveDir, skipSet, userLut, true);
            // Do not close: the image was opened by the user
        } else if (target.isDirectory()) {
            File[] files = target.listFiles();
            int nSkipped = 0;
            if (files != null) {
                for (File f : files) {
                    if (!f.isFile())
                        continue;
                    if (!f.getName().startsWith(PROB_PREFIX)) {
                        nSkipped++;
                        continue;
                    }
                    ImagePlus imp = IJ.openImage(f.getPath());
                    if (imp == null)
                        continue;
                    handleOneImage(imp, stripExt(f.getName()), saveDir, skipSet, userLut, false);
                    imp.close();
                }
            }
            if (nSkipped > 0)
                IJ.log("  Skipped " + nSkipped + " file(s) not starting with " + PROB_PREFIX);
        } else {
            ImagePlus imp = IJ.openImage(inputPath);
            if (imp != null) {
                handleOneImage(imp, stripExt(target.getName()), saveDir, skipSet, userLut, false);
                imp.close();
            }
        }

        // --- ④ Processing time log ---
        IJ.log("Total processing time: "
                + formatElapsed(System.currentTimeMillis() - startTime));
    }

    private void handleOneImage(ImagePlus imp, String fname, String saveDir,
                                HashSet<Integer> skipSet, LUT userLut, boolean showResult) {
        long imageStart = System.currentTimeMillis();
        IJ.showStatus("Decoding " + fname);

        int bitDepth = imp.getBitDepth();
        if (bitDepth == 24) {
            IJ.log("  [WARN] " + fname + ": RGB images are not supported — skipped");
            return;
        }

        ImageStack stack = imp.getStack();
        int w = imp.getWidth(), h = imp.getHeight(), n = w * h;
        int nSlices = stack.getSize();
        if (nSlices > 254) {
            IJ.log("  [WARN] " + fname + ": more than 254 slices cannot be stored as 8-bit labels — skipped");
            return;
        }

        // Warn about excluded slice numbers that do not exist in this image
        for (int z : skipSet) {
            if (z < 1 || z > nSlices)
                IJ.log("  [WARN] " + fname + ": excluded slice " + z
                        + " does not exist (image has " + nSlices + " slices) — ignored");
        }

        // Value that corresponds to a probability of 1.0 for this bit depth
        // (32-bit maps are assumed to hold probabilities in 0–1)
        double fullScale = (bitDepth == 8) ? 255.0 : (bitDepth == 16) ? 65535.0 : 1.0;
        double thr = (bitDepth == 32) ? minThresh : Math.round(minThresh * fullScale);

        // Per-pixel maximum and the slice where it occurs (first slice wins on ties)
        float[] maxVal = new float[n];
        byte[] label = new byte[n];

        for (int z = 1; z <= nSlices; z++) {
            IJ.showProgress(z, nSlices);
            if (skipSet.contains(z))
                continue;
            ImageProcessor ip = stack.getProcessor(z);
            if (bitDepth == 8 && isUniform128(ip)) {
                IJ.log("  " + fname + ": slice " + z + " is uniformly 128 (no classifier output) — skipped");
                continue;
            }
            for (int i = 0; i < n; i++) {
                float v = ip.getf(i);
                if (v > maxVal[i]) {
                    maxVal[i] = v;
                    label[i] = (byte) z;
                }
            }
        }

        for (int i = 0; i < n; i++) {
            if (maxVal[i] < thr)
                label[i] = 0;
        }

        // LUT: user file if given, otherwise the same palette as FluoCode Reader
        LUT lut = (userLut != null) ? userLut : makeDynamicLabelLUT(nSlices);

        ImagePlus out = new ImagePlus("Decoded_" + fname, new ByteProcessor(w, h, label, null));
        out.getProcessor().setLut(lut);
        new FileSaver(out).saveAsTiff(saveDir + File.separator + "Decoded_" + fname + ".tif");

        // For an open image, also show the result
        if (showResult)
            out.show();

        IJ.log("  Finished " + fname + " in "
                + formatElapsed(System.currentTimeMillis() - imageStart));
    }

    /** Input selection: an open image, an image file, or a folder */
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

    private HashSet<Integer> parseSkipSet(String s) {
        HashSet<Integer> set = new HashSet<>();
        if (s.isEmpty()) return set;
        for (String tok : s.split("[,;\\s]+")) {
            try { set.add(Integer.parseInt(tok.trim())); }
            catch (NumberFormatException ignored) {}
        }
        return set;
    }

    /** Removes the extension and a leading "Prob_" (Prob_sample.tif → sample) */
    private String stripExt(String name) {
        String base = name.replaceFirst("\\.[^.]+$", "");
        return base.startsWith(PROB_PREFIX) ? base.substring(PROB_PREFIX.length()) : base;
    }

    /** True if every pixel is 128 (classifier returned 0.5 everywhere) */
    private boolean isUniform128(ImageProcessor ip) {
        int n = ip.getPixelCount();
        for (int i = 0; i < n; i++) {
            if (ip.get(i) != 128)
                return false;
        }
        return true;
    }

    /** Formats elapsed time, e.g. "1 h 5 min 12 s" (hours only when needed) */
    private static String formatElapsed(long ms) {
        long s = ms / 1000;
        long h = s / 3600, m = (s / 60) % 60, sec = s % 60;
        if (h > 0)
            return h + " h " + m + " min " + sec + " s";
        return m + " min " + sec + " s";
    }

    private LUT makeDynamicLabelLUT(int n) {
        n = Math.min(254, Math.max(1, n));
        byte[] r = new byte[256], g = new byte[256], b = new byte[256];
        r[0] = g[0] = b[0] = (byte)255;   // 0 → white
        r[255] = g[255] = b[255] = 0;     // 255 → black

        double baseS = 0.8, baseV = 0.85, ds = 0.05, dv = 0.05;
        double startHue = 0.75;
        int start = (int)Math.round(startHue * n) % n;

        for (int i = 0; i < n; i++) {
            double h = (double)i / n;
            double s = baseS + ds * ((i % 2 == 0) ? 1 : -1);
            double v = baseV + dv * (((i + 1) % 2 == 0) ? 1 : -1);
            int rgb = Color.HSBtoRGB((float)h, (float)s, (float)v);

            int idx = (start - i) % n; if (idx < 0) idx += n;
            int label = 1 + idx;
            r[label] = (byte)((rgb >> 16) & 0xFF);
            g[label] = (byte)((rgb >>  8) & 0xFF);
            b[label] = (byte)( rgb        & 0xFF);
        }
        return new LUT(r, g, b);
    }
}