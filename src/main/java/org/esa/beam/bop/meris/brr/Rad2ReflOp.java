/*
 * $Id: Rad2ReflOp.java,v 1.1 2007/03/27 12:51:41 marcoz Exp $
 *
 * Copyright (C) 2007 by Brockmann Consult (info@brockmann-consult.de)
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation. This program is distributed in the hope it will
 * be useful, but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.esa.beam.bop.meris.brr;

import java.awt.Rectangle;
import java.util.HashMap;
import java.util.Map;

import org.esa.beam.dataio.envisat.EnvisatConstants;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.datamodel.RasterDataNode;
import org.esa.beam.framework.gpf.AbstractOperatorSpi;
import org.esa.beam.framework.gpf.GPF;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.Raster;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.framework.gpf.internal.DefaultOperatorContext;
import org.esa.beam.framework.gpf.operators.common.BandArithmeticOp;
import org.esa.beam.framework.gpf.operators.meris.MerisBasisOp;
import org.esa.beam.util.ProductUtils;
import org.esa.beam.util.math.MathUtils;

import com.bc.ceres.core.ProgressMonitor;

/**
 * Created by marcoz.
 *
 * @author marcoz
 * @version $Revision: 1.1 $ $Date: 2007/03/27 12:51:41 $
 */
public class Rad2ReflOp extends MerisBasisOp implements Constants {

    public static final String RHO_TOA_BAND_PREFIX = "rho_toa";

    private static final String MERIS_L2_CONF = "meris_l2_config.xml";

    private transient DpmConfig dpmConfig;
    private transient L2AuxData auxData;
    
    private transient Band[] radianceBands;
    private transient Band invalidBand;
    private transient RasterDataNode detectorIndexBand;
    private transient RasterDataNode sunZenihTPG;
    
    private transient Band[] rhoToaBands;
    
    @SourceProduct(alias="input")
    private Product sourceProduct;
    @TargetProduct
    private Product targetProduct;
    @Parameter
    private String configFile = MERIS_L2_CONF;

    public Rad2ReflOp(OperatorSpi spi) {
        super(spi);
    }

    @Override
	protected Product initialize(ProgressMonitor pm) throws OperatorException {
        try {
            dpmConfig = new DpmConfig(configFile);
        } catch (Exception e) {
            throw new OperatorException("Failed to load configuration from " + configFile + ":\n" + e.getMessage(), e);
        }
        try {
            auxData = new L2AuxData(dpmConfig, sourceProduct);
        } catch (Exception e) {
            throw new OperatorException("could not load L2Auxdata", e);
        }

        targetProduct = createCompatibleProduct(sourceProduct, "MER", "MER_L2");
        rhoToaBands = new Band[EnvisatConstants.MERIS_L1B_NUM_SPECTRAL_BANDS];
        radianceBands = new Band[EnvisatConstants.MERIS_L1B_NUM_SPECTRAL_BANDS];
        for (int i = 0; i < EnvisatConstants.MERIS_L1B_NUM_SPECTRAL_BANDS; i++) {
        	radianceBands[i] = sourceProduct.getBandAt(i);

            rhoToaBands[i] = targetProduct.addBand(RHO_TOA_BAND_PREFIX + "_" + (i + 1),
                                      ProductData.TYPE_FLOAT32);
            ProductUtils.copySpectralAttributes(radianceBands[i], rhoToaBands[i]);
            rhoToaBands[i].setNoDataValueUsed(true);
            rhoToaBands[i].setNoDataValue(BAD_VALUE);
        }
        detectorIndexBand = sourceProduct.getBand(EnvisatConstants.MERIS_DETECTOR_INDEX_DS_NAME);
        sunZenihTPG = sourceProduct.getTiePointGrid(EnvisatConstants.MERIS_SUN_ZENITH_DS_NAME);
        
		invalidBand = createBooleanBandForExpression("l1_flags.INVALID", pm);
        return targetProduct;
    }
    
    private Band createBooleanBandForExpression(String expression, ProgressMonitor pm) throws OperatorException {
		Map<String, Object> parameters = new HashMap<String, Object>();
        BandArithmeticOp.BandDescriptor[] bandDescriptors = new BandArithmeticOp.BandDescriptor[1];
        BandArithmeticOp.BandDescriptor bandDescriptor = new BandArithmeticOp.BandDescriptor();
		bandDescriptor.name = "bBand";
		bandDescriptor.expression = expression;
		bandDescriptor.type = ProductData.TYPESTRING_BOOLEAN;
		bandDescriptors[0] = bandDescriptor;
		parameters.put("bandDescriptors", bandDescriptors);
		
		Product invalidProduct = GPF.createProduct("BandArithmetic", parameters, sourceProduct, pm);
		DefaultOperatorContext context = (DefaultOperatorContext) getContext();
		context.addSourceProduct("x", invalidProduct);
		return invalidProduct.getBand("bBand");
	}


    @Override
    public void computeAllBands(Map<Band, Raster> targetRasters, Rectangle rectangle,
            ProgressMonitor pm) throws OperatorException {

        pm.beginTask("Processing frame...", rectangle.height);
        try {
        	Raster[] radiance = new Raster[radianceBands.length];
        	for (int i = 0; i < radiance.length; i++) {
        		radiance[i] = getRaster(radianceBands[i], rectangle);
            }
        	Raster detectorIndex = getRaster(detectorIndexBand, rectangle);
        	Raster sza = getRaster(sunZenihTPG, rectangle);
        	Raster isInvalid = getRaster(invalidBand, rectangle);

            Raster[] rhoToa = new Raster[rhoToaBands.length];
            for (int i = 0; i < rhoToaBands.length; i++) {
                rhoToa[i] = targetRasters.get(rhoToaBands[i]);
            }

            for (int y = rectangle.y; y < rectangle.y + rectangle.height; y++) {
				for (int x = rectangle.x; x < rectangle.x + rectangle.width; x++) {
					if (isInvalid.getBoolean(x, y)) {
						for (int bandId = 0; bandId < L1_BAND_NUM; bandId++) {
							rhoToa[bandId].setFloat(x, y, BAD_VALUE);
						}
					} else {
						final double constantTerm = (Math.PI / Math.cos(sza
								.getFloat(x, y) * MathUtils.DTOR)) * auxData.seasonal_factor;
						for (int bandId = 0; bandId < L1_BAND_NUM; bandId++) {
							// DPM #2.1.4-1
							final float aRhoToa = (float) ((radiance[bandId]
									.getFloat(x, y) * constantTerm) / auxData.detector_solar_irradiance[bandId][detectorIndex
									.getInt(x, y)]);
							rhoToa[bandId].setFloat(x, y, aRhoToa);
						}
					}
				}
				pm.worked(1);
			}
        } catch (Exception e) {
            throw new OperatorException(e);
        } finally {
            pm.done();
        }
    }


    public static class Spi extends AbstractOperatorSpi {
        public Spi() {
            super(Rad2ReflOp.class, "Meris.Rad2Refl");
        }
    }
}
