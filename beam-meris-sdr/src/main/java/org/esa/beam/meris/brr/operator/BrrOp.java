package org.esa.beam.meris.brr.operator;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.beam.dataio.envisat.EnvisatConstants;
import org.esa.beam.framework.datamodel.*;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.Tile;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.meris.brr.dpm.*;
import org.esa.beam.meris.l2auxdata.Constants;
import org.esa.beam.meris.l2auxdata.L2AuxData;
import org.esa.beam.meris.l2auxdata.L2AuxDataProvider;
import org.esa.beam.util.BitSetter;
import org.esa.beam.util.ProductUtils;

import java.awt.*;
import java.util.Map;


@OperatorMetadata(alias = "Meris.Brr",
        version = "2.3.4",
        authors = "R. Santer, Marco Zühlke, Tom Block",
        copyright = "(c) European Space Agency",
        description = "Performs the Rayleigh correction on a MERIS L1b product.")
public class BrrOp extends BrrBasisOp {

    // source product
    private RasterDataNode[] tpGrids;
    private RasterDataNode[] l1bRadiance;
    private RasterDataNode detectorIndex;
    private RasterDataNode l1bFlags;

    private final ThreadLocal<DpmPixel[]> frame = new ThreadLocal<DpmPixel[]>() {
        @Override
        protected DpmPixel[] initialValue() {
            return new DpmPixel[0];
        }
    };
    private final ThreadLocal<DpmPixel[][]> block = new ThreadLocal<DpmPixel[][]>() {
        @Override
        protected DpmPixel[][] initialValue() {
            return new DpmPixel[0][0];
        }
    };

    // target product
    protected Band l2FlagsP1;
    protected Band l2FlagsP2;
    protected Band l2FlagsP3;

    protected Band[] brrReflecBands = new Band[Constants.L1_BAND_NUM];
    protected Band[] toaReflecBands = new Band[Constants.L1_BAND_NUM];

    @SourceProduct(alias = "MERIS_L1b")
    private Product sourceProduct;
    @TargetProduct
    private Product targetProduct;

    @Parameter(description = "Write L1 flags to the target product.", defaultValue = "false")
    public boolean copyL1Flags = false;
    @Parameter(description = "Write TOA reflectances to the target product.", defaultValue = "false")
    public boolean outputToar = false;

    @Parameter(defaultValue = "ALL_SURFACES",
               valueSet = {"ALL_SURFACES", "LAND", "WATER"},
               label = "Perform Rayleigh correction over",
               description = "Specify the surface where the Rayleigh correction shall be performed")
    private CorrectionSurfaceEnum correctionSurface;

    private L2AuxData auxData;

    @Override
    public void initialize() throws OperatorException {
        // todo - tell someone else that we need a 4x4 subwindow

        checkInputProduct(sourceProduct);
        prepareSourceProducts();

        targetProduct = createCompatibleProduct(sourceProduct, "BRR", "BRR");
        // set tile-size smaller than the one that GPF might associate. We need to allocate A LOT of memory per tile.
        // preferred tile-size must be odd in x-direction to cope with the 4x4 window required by the algo and the odd
        // line length of a meris product. Tiles of width=1 force exceptions. tb 2014-01-24
        targetProduct.setPreferredTileSize(129, 128);
        if (copyL1Flags) {
            ProductUtils.copyFlagBands(sourceProduct, targetProduct, true);
        }

        createOutputBands(brrReflecBands, "brr");
        if (outputToar) {
            createOutputBands(toaReflecBands, "toar");
        }

        l2FlagsP1 = addFlagsBand(createFlagCodingP1(), 0.0, 1.0, 0.5);
        l2FlagsP2 = addFlagsBand(createFlagCodingP2(), 0.2, 0.7, 0.0);
        l2FlagsP3 = addFlagsBand(createFlagCodingP3(), 0.8, 0.1, 0.3);

        initAlgorithms(sourceProduct);
    }

    private void initAlgorithms(Product inputProduct) throws IllegalArgumentException {
        try {
            auxData = L2AuxDataProvider.getInstance().getAuxdata(inputProduct);
        } catch (Exception e) { // todo handle IOException and DpmException
            e.printStackTrace();
            throw new IllegalArgumentException(e.getMessage());
        }
    }

    protected void prepareSourceProducts() {
        final int numTPGrids = EnvisatConstants.MERIS_TIE_POINT_GRID_NAMES.length;
        tpGrids = new RasterDataNode[numTPGrids];
        for (int i = 0; i < numTPGrids; i++) {
            tpGrids[i] = sourceProduct.getTiePointGrid(EnvisatConstants.MERIS_TIE_POINT_GRID_NAMES[i]);
        }

// mz 2007-11-22 at the moment lat and lon are not used for any computation        
//        if (sourceProduct.getProductType().equals(EnvisatConstants.MERIS_FSG_L1B_PRODUCT_TYPE_NAME)) {
//            tpGrids[Constants.LATITUDE_TPG_INDEX] = sourceProduct.getBand("corr_latitude");
//            tpGrids[Constants.LONGITUDE_TPG_INDEX] = sourceProduct.getBand("corr_longitude");
//        }

        l1bRadiance = new RasterDataNode[EnvisatConstants.MERIS_L1B_SPECTRAL_BAND_NAMES.length];
        for (int i = 0; i < l1bRadiance.length; i++) {
            l1bRadiance[i] = sourceProduct.getBand(EnvisatConstants.MERIS_L1B_SPECTRAL_BAND_NAMES[i]);
        }
        detectorIndex = sourceProduct.getBand(EnvisatConstants.MERIS_DETECTOR_INDEX_DS_NAME);
        l1bFlags = sourceProduct.getBand(EnvisatConstants.MERIS_L1B_FLAGS_DS_NAME);
    }

    @Override
    public void computeTileStack(Map<Band, Tile> targetTiles, Rectangle rectangle, ProgressMonitor pm) throws OperatorException {
//        System.out.println("rectangle = " + rectangle);

        L1bDataExtraction extdatl1 = new L1bDataExtraction(auxData);
        GaseousAbsorptionCorrection gaz_cor = new GaseousAbsorptionCorrection(auxData);
        PixelIdentification pixelid = new PixelIdentification(auxData, gaz_cor);
        RayleighCorrection ray_cor = new RayleighCorrection(auxData);
        CloudClassification classcloud = new CloudClassification(auxData, ray_cor);
        AtmosphericCorrectionLand landac = new AtmosphericCorrectionLand(ray_cor);

//        pixelid.setCorrectWater(correctWater);
        pixelid.setCorrectionSurface(correctionSurface);
//        landac.setCorrectionSurface(correctWater);
        landac.setCorrectionSurface(correctionSurface);

        final FrameAndBlock frameAndBlock = getFrameAndBlock(rectangle);
        final DpmPixel[] frameLocal = frameAndBlock.frame;
        final DpmPixel[][] blockLocal = frameAndBlock.block;

        final int frameSize = rectangle.height * rectangle.width;
        int[] l2FlagsP1Frame = new int[frameSize];
        int[] l2FlagsP2Frame = new int[frameSize];
        int[] l2FlagsP3Frame = new int[frameSize];

        Tile[] l1bTiePoints = new Tile[tpGrids.length];
        for (int i = 0; i < tpGrids.length; i++) {
            l1bTiePoints[i] = getSourceTile(tpGrids[i], rectangle);
        }
        Tile[] l1bRadiances = new Tile[l1bRadiance.length];
        for (int i = 0; i < l1bRadiance.length; i++) {
            l1bRadiances[i] = getSourceTile(l1bRadiance[i], rectangle);
        }
        Tile l1bDetectorIndex = getSourceTile(detectorIndex, rectangle);
        Tile l1bFlagRaster = getSourceTile(l1bFlags, rectangle);

        for (int pixelIndex = 0; pixelIndex < frameSize; pixelIndex++) {
            DpmPixel pixel = frameLocal[pixelIndex];
            extdatl1.l1_extract_pixbloc(pixel,
                    rectangle.x + pixel.i,
                    rectangle.y + pixel.j,
                    l1bTiePoints,
                    l1bRadiances,
                    l1bDetectorIndex,
                    l1bFlagRaster);

            if (!BitSetter.isFlagSet(pixel.l2flags, Constants.F_INVALID)) {
                pixelid.rad2reflect(pixel);
                classcloud.classify_cloud(pixel);
            }
        }

        for (int iPL1 = 0; iPL1 < rectangle.height; iPL1 += Constants.SUBWIN_HEIGHT) {
            for (int iPC1 = 0; iPC1 < rectangle.width; iPC1 += Constants.SUBWIN_WIDTH) {
                final int iPC2 = Math.min(rectangle.width, iPC1 + Constants.SUBWIN_WIDTH) - 1;
                final int iPL2 = Math.min(rectangle.height, iPL1 + Constants.SUBWIN_HEIGHT) - 1;
                pixelid.pixel_classification(blockLocal, iPC1, iPC2, iPL1, iPL2);
                landac.landAtmCor(blockLocal, iPC1, iPC2, iPL1, iPL2);
            }
        }

        for (int iP = 0; iP < frameLocal.length; iP++) {
            DpmPixel pixel = frameLocal[iP];
            l2FlagsP1Frame[iP] = (int) ((pixel.l2flags & 0x00000000ffffffffL));
            l2FlagsP2Frame[iP] = (int) ((pixel.l2flags & 0xffffffff00000000L) >> 32);
            l2FlagsP3Frame[iP] = pixel.ANNOT_F;
        }


        for (int bandIndex = 0; bandIndex < brrReflecBands.length; bandIndex++) {
            if (isValidRhoSpectralIndex(bandIndex)) {
                ProductData data = targetTiles.get(brrReflecBands[bandIndex]).getRawSamples();
                float[] ddata = (float[]) data.getElems();
                for (int iP = 0; iP < rectangle.width * rectangle.height; iP++) {
                    ddata[iP] = (float) frameLocal[iP].rho_top[bandIndex];
                }
                targetTiles.get(brrReflecBands[bandIndex]).setRawSamples(data);
            }
        }
        if (outputToar) {
            for (int bandIndex = 0; bandIndex < toaReflecBands.length; bandIndex++) {
                ProductData data = targetTiles.get(toaReflecBands[bandIndex]).getRawSamples();
                float[] ddata = (float[]) data.getElems();
                for (int iP = 0; iP < rectangle.width * rectangle.height; iP++) {
                    ddata[iP] = (float) frameLocal[iP].rho_toa[bandIndex];
                }
                targetTiles.get(toaReflecBands[bandIndex]).setRawSamples(data);
            }
        }
        ProductData flagData = targetTiles.get(l2FlagsP1).getRawSamples();
        int[] intFlag = (int[]) flagData.getElems();
        System.arraycopy(l2FlagsP1Frame, 0, intFlag, 0, rectangle.width * rectangle.height);
        targetTiles.get(l2FlagsP1).setRawSamples(flagData);

        flagData = targetTiles.get(l2FlagsP2).getRawSamples();
        intFlag = (int[]) flagData.getElems();
        System.arraycopy(l2FlagsP2Frame, 0, intFlag, 0, rectangle.width * rectangle.height);
        targetTiles.get(l2FlagsP2).setRawSamples(flagData);

        flagData = targetTiles.get(l2FlagsP3).getRawSamples();
        intFlag = (int[]) flagData.getElems();
        System.arraycopy(l2FlagsP3Frame, 0, intFlag, 0, rectangle.width * rectangle.height);
        targetTiles.get(l2FlagsP3).setRawSamples(flagData);
    }

    protected Band addFlagsBand(final FlagCoding flagCodingP1, final double rf1, final double gf1, final double bf1) {
        addFlagCodingAndCreateBMD(flagCodingP1, rf1, gf1, bf1);
        final Band l2FlagsP1Band = new Band(flagCodingP1.getName(), ProductData.TYPE_INT32,
                targetProduct.getSceneRasterWidth(), targetProduct.getSceneRasterHeight());
        l2FlagsP1Band.setSampleCoding(flagCodingP1);
        targetProduct.addBand(l2FlagsP1Band);
        return l2FlagsP1Band;
    }

    protected void addFlagCodingAndCreateBMD(FlagCoding flagCodingP1, double rf1, double gf1, double bf1) {
        targetProduct.getFlagCodingGroup().add(flagCodingP1);
        for (int i = 0; i < flagCodingP1.getNumAttributes(); i++) {
            final MetadataAttribute attribute = flagCodingP1.getAttributeAt(i);
            final double a = 2 * Math.PI * (i / 31.0);
            final Color color = new Color((float) (0.5 + 0.5 * Math.sin(a + rf1 * Math.PI)),
                    (float) (0.5 + 0.5 * Math.sin(a + gf1 * Math.PI)),
                    (float) (0.5 + 0.5 * Math.sin(a + bf1 * Math.PI)));
            targetProduct.addBitmaskDef(new BitmaskDef(attribute.getName(),
                    null,
                    flagCodingP1.getName() + "." + attribute.getName(),
                    color,
                    0.4F));
        }
    }

    protected void createOutputBands(Band[] bands, final String name) {
        final int sceneWidth = targetProduct.getSceneRasterWidth();
        final int sceneHeight = targetProduct.getSceneRasterHeight();

        for (int bandId = 0; bandId < bands.length; bandId++) {
            if (isValidRhoSpectralIndex(bandId) || name.equals("toar")) {
                Band aNewBand = new Band(name + "_" + (bandId + 1), ProductData.TYPE_FLOAT32, sceneWidth,
                        sceneHeight);
                aNewBand.setNoDataValueUsed(true);
                aNewBand.setNoDataValue(-1);
                aNewBand.setSpectralBandIndex(sourceProduct.getBandAt(bandId).getSpectralBandIndex());
                aNewBand.setSpectralWavelength(sourceProduct.getBandAt(bandId).getSpectralWavelength());
                aNewBand.setSpectralBandwidth(sourceProduct.getBandAt(bandId).getSpectralBandwidth());
                targetProduct.addBand(aNewBand);
                bands[bandId] = aNewBand;
            }
        }
    }

    protected void checkInputProduct(Product inputProduct) throws IllegalArgumentException {
        String name;

        for (int i = 0; i < EnvisatConstants.MERIS_TIE_POINT_GRID_NAMES.length; i++) {
            name = EnvisatConstants.MERIS_TIE_POINT_GRID_NAMES[i];
            if (inputProduct.getTiePointGrid(name) == null) {
                throw new IllegalArgumentException("Invalid input product. Missing tie point grid '" + name + "'.");
            }
        }

        for (int i = 0; i < EnvisatConstants.MERIS_L1B_SPECTRAL_BAND_NAMES.length; i++) {
            name = EnvisatConstants.MERIS_L1B_SPECTRAL_BAND_NAMES[i];
            if (inputProduct.getBand(name) == null) {
                throw new IllegalArgumentException("Invalid input product. Missing band '" + name + "'.");
            }
        }

        name = EnvisatConstants.MERIS_DETECTOR_INDEX_DS_NAME;
        if (inputProduct.getBand(name) == null) {
            throw new IllegalArgumentException("Invalid input product. Missing dataset '" + name + "'.");
        }

        name = EnvisatConstants.MERIS_L1B_FLAGS_DS_NAME;
        if (inputProduct.getBand(name) == null) {
            throw new IllegalArgumentException("Invalid input product. Missing dataset '" + name + "'.");
        }
    }

    protected static FlagCoding createFlagCodingP1() {
        FlagCoding flagCoding = new FlagCoding("l2_flags_p1");
        flagCoding.addFlag("F_BRIGHT", BitSetter.setFlag(0, Constants.F_BRIGHT), null);
        flagCoding.addFlag("F_CASE2_S", BitSetter.setFlag(0, Constants.F_CASE2_S), null);
        flagCoding.addFlag("F_CASE2ANOM", BitSetter.setFlag(0, Constants.F_CASE2ANOM), null);
        flagCoding.addFlag("F_CASE2Y", BitSetter.setFlag(0, Constants.F_CASE2Y), null);
        flagCoding.addFlag("F_CHL1RANGE_IN", BitSetter.setFlag(0, Constants.F_CHL1RANGE_IN), null);
        flagCoding.addFlag("F_CHL1RANGE_OUT", BitSetter.setFlag(0, Constants.F_CHL1RANGE_OUT), null);
        flagCoding.addFlag("F_CIRRUS", BitSetter.setFlag(0, Constants.F_CIRRUS), null);
        flagCoding.addFlag("F_CLOUD", BitSetter.setFlag(0, Constants.F_CLOUD), null);
        flagCoding.addFlag("F_CLOUDPART", BitSetter.setFlag(0, Constants.F_CLOUDPART), null);
        flagCoding.addFlag("F_COASTLINE", BitSetter.setFlag(0, Constants.F_COASTLINE), null);
        flagCoding.addFlag("F_COSMETIC", BitSetter.setFlag(0, Constants.F_COSMETIC), null);
        flagCoding.addFlag("F_DDV", BitSetter.setFlag(0, Constants.F_DDV), null);
        flagCoding.addFlag("F_DUPLICATED", BitSetter.setFlag(0, Constants.F_DUPLICATED), null);
        flagCoding.addFlag("F_HIINLD", BitSetter.setFlag(0, Constants.F_HIINLD), null);
        flagCoding.addFlag("F_ICE_HIGHAERO", BitSetter.setFlag(0, Constants.F_ICE_HIGHAERO), null);
        flagCoding.addFlag("F_INVALID", BitSetter.setFlag(0, Constants.F_INVALID), null);
        flagCoding.addFlag("F_ISLAND", BitSetter.setFlag(0, Constants.F_ISLAND), null);
        flagCoding.addFlag("F_LAND", BitSetter.setFlag(0, Constants.F_LAND), null);
        flagCoding.addFlag("F_LANDCONS", BitSetter.setFlag(0, Constants.F_LANDCONS), null);
        flagCoding.addFlag("F_LOINLD", BitSetter.setFlag(0, Constants.F_LOINLD), null);
        flagCoding.addFlag("F_MEGLINT", BitSetter.setFlag(0, Constants.F_MEGLINT), null);
        flagCoding.addFlag("F_ORINP1", BitSetter.setFlag(0, Constants.F_ORINP1), null);
        flagCoding.addFlag("F_ORINP2", BitSetter.setFlag(0, Constants.F_ORINP2), null);
        flagCoding.addFlag("F_ORINPWV", BitSetter.setFlag(0, Constants.F_ORINPWV), null);
        flagCoding.addFlag("F_OROUT1", BitSetter.setFlag(0, Constants.F_OROUT1), null);
        flagCoding.addFlag("F_OROUT2", BitSetter.setFlag(0, Constants.F_OROUT2), null);
        flagCoding.addFlag("F_OROUTWV", BitSetter.setFlag(0, Constants.F_OROUTWV), null);
        flagCoding.addFlag("F_SUSPECT", BitSetter.setFlag(0, Constants.F_SUSPECT), null);
        flagCoding.addFlag("F_UNCGLINT", BitSetter.setFlag(0, Constants.F_UNCGLINT), null);
        flagCoding.addFlag("F_WHITECAPS", BitSetter.setFlag(0, Constants.F_WHITECAPS), null);
        flagCoding.addFlag("F_WVAP", BitSetter.setFlag(0, Constants.F_WVAP), null);
        flagCoding.addFlag("F_ACFAIL", BitSetter.setFlag(0, Constants.F_ACFAIL), null);
        return flagCoding;
    }

    protected static FlagCoding createFlagCodingP2() {
        FlagCoding flagCoding = new FlagCoding("l2_flags_p2");
        flagCoding.addFlag("F_CONSOLID", BitSetter.setFlag(0, Constants.F_CONSOLID), null);
        flagCoding.addFlag("F_ORINP0", BitSetter.setFlag(0, Constants.F_ORINP0), null);
        flagCoding.addFlag("F_OROUT0", BitSetter.setFlag(0, Constants.F_OROUT0), null);
        flagCoding.addFlag("F_LOW_NN_P", BitSetter.setFlag(0, Constants.F_LOW_NN_P), null);
        flagCoding.addFlag("F_PCD_NN_P", BitSetter.setFlag(0, Constants.F_PCD_NN_P), null);
        flagCoding.addFlag("F_LOW_POL_P", BitSetter.setFlag(0, Constants.F_LOW_POL_P), null);
        flagCoding.addFlag("F_PCD_POL_P", BitSetter.setFlag(0, Constants.F_PCD_POL_P), null);
        flagCoding.addFlag("F_CONFIDENCE_P", BitSetter.setFlag(0, Constants.F_CONFIDENCE_P), null);
        flagCoding.addFlag("F_SLOPE_1", BitSetter.setFlag(0, Constants.F_SLOPE_1), null);
        flagCoding.addFlag("F_SLOPE_2", BitSetter.setFlag(0, Constants.F_SLOPE_2), null);
        flagCoding.addFlag("F_UNCERTAIN", BitSetter.setFlag(0, Constants.F_UNCERTAIN), null);
        flagCoding.addFlag("F_SUN70", BitSetter.setFlag(0, Constants.F_SUN70), null);
        flagCoding.addFlag("F_WVHIGLINT", BitSetter.setFlag(0, Constants.F_WVHIGLINT), null);
        flagCoding.addFlag("F_TOAVIVEG", BitSetter.setFlag(0, Constants.F_TOAVIVEG), null);
        flagCoding.addFlag("F_TOAVIBAD", BitSetter.setFlag(0, Constants.F_TOAVIBAD), null);
        flagCoding.addFlag("F_TOAVICSI", BitSetter.setFlag(0, Constants.F_TOAVICSI), null);
        flagCoding.addFlag("F_TOAVIWS", BitSetter.setFlag(0, Constants.F_TOAVIWS), null);
        flagCoding.addFlag("F_TOAVIBRIGHT", BitSetter.setFlag(0, Constants.F_TOAVIBRIGHT), null);
        flagCoding.addFlag("F_TOAVIINVALREC", BitSetter.setFlag(0, Constants.F_TOAVIINVALREC), null);
        return flagCoding;
    }

    protected static FlagCoding createFlagCodingP3() {
        FlagCoding flagCoding = new FlagCoding("l2_flags_p3");
        for (int i = 0; i < Constants.L1_BAND_NUM; i++) {
            flagCoding.addFlag("F_INVALID_REFLEC_" + (i + 1), BitSetter.setFlag(0, i), null);
        }
        return flagCoding;
    }

    static boolean isValidRhoSpectralIndex(int i) {
        return i >= Constants.bb1 && i < Constants.bb15 && i != Constants.bb11;
    }

    private FrameAndBlock getFrameAndBlock(Rectangle rectangle) {
        final FrameAndBlock frameAndBlock = new FrameAndBlock();
        final int frameSize = rectangle.width * rectangle.height;

        DpmPixel[] frameLocal = frame.get();
        DpmPixel[][] blockLocal = block.get();
        if (frameLocal.length != frameSize) {
            // reallocate
            frameLocal = new DpmPixel[frameSize];
            blockLocal = new DpmPixel[rectangle.height][rectangle.width];
            for (int pixelIndex = 0; pixelIndex < frameSize; pixelIndex++) {
                final DpmPixel pixel = new DpmPixel(pixelIndex % rectangle.width, pixelIndex / rectangle.width);
                frameLocal[pixelIndex] = blockLocal[pixel.j][pixel.i] = pixel;
                frame.set(frameLocal);
                block.set(blockLocal);
            }
        } else {
            for (int pixelIndex = 0; pixelIndex < frameSize; pixelIndex++) {
                frameLocal[pixelIndex].reset(pixelIndex % rectangle.width, pixelIndex / rectangle.width);
            }
        }

        frameAndBlock.frame = frameLocal;
        frameAndBlock.block = blockLocal;

        return frameAndBlock;
    }

    private class FrameAndBlock {
        DpmPixel[] frame;
        DpmPixel[][] block;

    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(BrrOp.class);
        }
    }
}