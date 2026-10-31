import ij.*;
import ij.process.*;
import ij.plugin.filter.*;
import ij.gui.*;
import ij.measure.ResultsTable;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.*;

public class FluoCode_Region_Counter implements PlugInFilter {
    private ImagePlus imp;

    private static int nCodes = 21;               // Number of FluoCodes counted (1..nCodes); remembered within a session

    public int setup(String arg, ImagePlus imp) {
        this.imp = imp;
        return DOES_8G + DOES_16 + DOES_32; // Support for 8-bit, 16-bit, and 32-bit grayscale images
    }

    public void run(ImageProcessor ip) {
        // Read region names from the CSV file
        String csvFilePath = "/value_name_.csv"; // Resource path inside the JAR
        Map<Integer, String> valueMap = readValuesFromCSV(csvFilePath);
        if (valueMap.isEmpty()) return;

        // Image selection
        GenericDialog gd = new GenericDialog("Select Images");
        String[] imageTitles = WindowManager.getImageTitles();
        // Allen Atlas region label image
        gd.addChoice("Allen Atlas:", imageTitles, imageTitles[0]);
        // Decoded FluoCode label image
        gd.addChoice("Decoded FluoCodes:", imageTitles, imageTitles[0]);
        // Number of FluoCode labels to count (e.g., 21 for 7 channels = 21 channel pairs)
        gd.addNumericField("Number of FluoCodes", nCodes, 0);

        gd.showDialog();
        if (gd.wasCanceled()) return;
        ImagePlus segmentationResultImage = WindowManager.getImage(gd.getNextChoice());
        ImagePlus rawImage = WindowManager.getImage(gd.getNextChoice());
        double nCodesInput = gd.getNextNumber();

        if (Double.isNaN(nCodesInput) || nCodesInput < 1 || nCodesInput > 254) {
            IJ.error("Number of FluoCodes", "Number of FluoCodes must be between 1 and 254.");
            return;
        }
        nCodes = (int) Math.round(nCodesInput);

        // Check size and slice count of the two selected images (not the active image)
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

        // Region selection
        List<String> selectedValues = showMultiSelectionDialog(new ArrayList<>(valueMap.values()));
        List<String> regionNames = new ArrayList<>();
        List<Integer> regionKeys = new ArrayList<>();
        for (String selectedValue : selectedValues) {
            Integer valueKey = getKeyByValue(valueMap, selectedValue);
            if (valueKey == null) {
                IJ.log("No corresponding number found for: " + selectedValue);
                continue;
            }
            if (valueKey < 0 || valueKey > 65535) {
                IJ.log("Region value out of range (0–65535): " + selectedValue);
                continue;
            }
            regionNames.add(selectedValue);
            regionKeys.add(valueKey);
        }
        if (regionKeys.isEmpty()) return;

        // Region value → index among the selected regions (-1 if not selected)
        int nRegions = regionKeys.size();
        int[] slotOf = new int[65536];
        Arrays.fill(slotOf, -1);
        for (int r = 0; r < nRegions; r++) {
            slotOf[regionKeys.get(r)] = r;
        }

        long[] totalCounts = new long[nRegions];          // total pixels per region
        long[][] pixelCounts = new long[nRegions][nCodes];  // pixels per region and per FluoCode value 1..nCodes
        long nAboveRange = 0;                               // pixels with labels above nCodes (255 excluded)
        int maxLabelFound = 0;

        ImageStack segStack = segmentationResultImage.getStack();
        ImageStack rawStack = rawImage.getStack();

        // Read each slice once and count all selected regions in one pass
        for (int slice = 1; slice <= nSlices; slice++) {
            ImageProcessor segmentationResultProcessor = segStack.getProcessor(slice);
            ImageProcessor rawProcessor = rawStack.getProcessor(slice);

            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int segmentationValue = segmentationResultProcessor.getPixel(x, y);
                    if (segmentationValue < 0 || segmentationValue >= slotOf.length) continue;
                    int r = slotOf[segmentationValue];
                    if (r < 0) continue; // region not selected
                    totalCounts[r]++;
                    int rawValue = (int) rawProcessor.getPixelValue(x, y);
                    if (rawValue >= 1 && rawValue <= nCodes) {
                        pixelCounts[r][rawValue - 1]++;
                    } else if (rawValue > nCodes && rawValue != 255) {
                        nAboveRange++;
                        if (rawValue > maxLabelFound) maxLabelFound = rawValue;
                    }
                }
            }
        }

        // Results table
        ResultsTable rt = new ResultsTable();
        for (int r = 0; r < nRegions; r++) {
            rt.incrementCounter();
            rt.addValue("Region", regionNames.get(r));
            rt.addValue("Pixel Counts", totalCounts[r]);

            // Pixel count for each FluoCode value 1..nCodes
            for (int i = 0; i < nCodes; i++) {
                rt.addValue("Value " + (i + 1) + " Pixel Count", pixelCounts[r][i]);
            }
        }
        rt.show("Results");

        // Warn if labels above the specified number were found
        if (nAboveRange > 0) {
            IJ.log("[WARN] " + nAboveRange + " pixel(s) in the selected regions have labels above "
                    + nCodes + " (highest: " + maxLabelFound + ") and were not counted per value. "
                    + "Check \"Number of FluoCodes\".");
        }
    }

    private Map<Integer, String> readValuesFromCSV(String filePath) {
        Map<Integer, String> valueMap = new LinkedHashMap<>();
        try (InputStream is = getClass().getResourceAsStream(filePath);
             BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length == 2) {
                    int key = Integer.parseInt(parts[0].trim());
                    String value = parts[1].trim();
                    valueMap.put(key, value);
                }
            }
        } catch (IOException | NumberFormatException e) {
            IJ.log("Error reading CSV file: " + e.getMessage());
        }
        return valueMap;
    }

    private List<String> showMultiSelectionDialog(List<String> options) {
        JList<String> list = new JList<>(options.toArray(new String[0]));
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        JScrollPane scrollPane = new JScrollPane(list);
        JOptionPane.showMessageDialog(null, scrollPane, "Select Values", JOptionPane.PLAIN_MESSAGE);
        return list.getSelectedValuesList();
    }

    private Integer getKeyByValue(Map<Integer, String> map, String value) {
        for (Map.Entry<Integer, String> entry : map.entrySet()) {
            if (entry.getValue().equals(value)) {
                return entry.getKey();
            }
        }
        return null;
    }
}