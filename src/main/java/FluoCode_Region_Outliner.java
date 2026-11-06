import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import ij.process.ImageProcessor;
import ij.process.LUT;

/**
 * Displays a decoded FluoCode image with the boundaries of every region of a
 * segmentation image (e.g. the Allen Atlas) drawn on top. Display only: neither input
 * image is modified.
 */
public class FluoCode_Region_Outliner implements PlugIn {

    private static final int OUTLINE_VALUE = 250; // Outline value (255 is avoided: it marks saturated pixels in decoded FluoCode images)
    private static final int OUTLINE_GRAY = 100;  // Outline color RGB(100,100,100)
    private static final int OUTSIDE_VALUE = 251; // Outside the atlas (atlas value 0); shown black, separate from saturation (255)

    private static boolean thickenOutlines = false; // remembered within a session

    public void run(String arg) {
        String[] imageTitles = WindowManager.getImageTitles();
        if (imageTitles.length < 2) {
            IJ.error("Region boundaries",
                    "Open both the Allen Atlas and the Decoded FluoCodes image first.");
            return;
        }

        GenericDialog gd = new GenericDialog("Draw Region Boundaries");
        // Allen Atlas region label image
        gd.addChoice("Allen Atlas:", imageTitles, imageTitles[0]);
        // Decoded FluoCode image
        gd.addChoice("Decoded FluoCodes:", imageTitles, imageTitles[0]);
        gd.addCheckbox("Thicken boundaries by 1 px", thickenOutlines);

        gd.showDialog();
        if (gd.wasCanceled()) return;
        ImagePlus segmentationResultImage = WindowManager.getImage(gd.getNextChoice());
        ImagePlus rawImage = WindowManager.getImage(gd.getNextChoice());
        thickenOutlines = gd.getNextBoolean();

        // Check size and slice count of the two selected images
        int w = rawImage.getWidth();
        int h = rawImage.getHeight();
        int nSlices = rawImage.getStackSize();
        if (segmentationResultImage.getWidth() != w
                || segmentationResultImage.getHeight() != h
                || segmentationResultImage.getStackSize() != nSlices) {
            IJ.error("Image size mismatch",
                    "Allen Atlas and Decoded FluoCodes must have the same size and number of slices.");
            return;
        }
        if (rawImage.getBitDepth() != 8) {
            IJ.error("Region boundaries",
                    "Region boundaries require an 8-bit Decoded FluoCodes image.");
            return;
        }

        ImageStack segStack = segmentationResultImage.getStack();
        ImageStack rawStack = rawImage.getStack();
        ImageStack outlineStack = new ImageStack(w, h);

        // Find Edges (Sobel) on the Allen Atlas; pixels >= 1 are written into a copy of the Decoded FluoCodes
        // Pixels outside the atlas (atlas value 0) are set to OUTSIDE_VALUE first; outlines are drawn on top
        for (int slice = 1; slice <= nSlices; slice++) {
            ImageProcessor segmentationResultProcessor = segStack.getProcessor(slice);
            ImageProcessor rawProcessor = rawStack.getProcessor(slice);

            ImageProcessor edges = segmentationResultProcessor.duplicate(); // copy so the original stack is not modified
            edges.findEdges();
            boolean[] edgeMask = new boolean[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    edgeMask[y * w + x] = edges.getPixelValue(x, y) >= 1;
                }
            }
            if (thickenOutlines) {
                edgeMask = dilate1px(edgeMask, w, h);
            }
            ImageProcessor outlineProcessor = rawProcessor.duplicate();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (segmentationResultProcessor.getPixel(x, y) == 0) {
                        outlineProcessor.set(x, y, OUTSIDE_VALUE);
                    }
                    if (edgeMask[y * w + x]) {
                        outlineProcessor.set(x, y, OUTLINE_VALUE);
                    }
                }
            }
            outlineStack.addSlice(rawStack.getSliceLabel(slice), outlineProcessor);
        }

        ImagePlus out = new ImagePlus("Boundaries_" + rawImage.getTitle(), outlineStack);
        out.setLut(makeOutlineLUT(rawImage.getProcessor().getLut()));
        out.show();
    }

    /** Grows the outline mask by 1 px in every direction (3x3 neighborhood, 8-connected) */
    private boolean[] dilate1px(boolean[] mask, int w, int h) {
        boolean[] out = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!mask[y * w + x]) continue;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= w) continue;
                        out[yy * w + xx] = true;
                    }
                }
            }
        }
        return out;
    }

    /** Keeps the Decoded FluoCodes LUT; sets the outline value to gray and the outside value to black */
    private LUT makeOutlineLUT(LUT base) {
        byte[] r = new byte[256];
        byte[] g = new byte[256];
        byte[] b = new byte[256];
        base.getReds(r);
        base.getGreens(g);
        base.getBlues(b);
        r[OUTLINE_VALUE] = (byte) OUTLINE_GRAY;
        g[OUTLINE_VALUE] = (byte) OUTLINE_GRAY;
        b[OUTLINE_VALUE] = (byte) OUTLINE_GRAY;
        r[OUTSIDE_VALUE] = 0;
        g[OUTSIDE_VALUE] = 0;
        b[OUTSIDE_VALUE] = 0;
        return new LUT(r, g, b);
    }
}
