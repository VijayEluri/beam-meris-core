/*
 * $Id: GaseousAbsorptionCorrection.java,v 1.1 2007/03/27 12:51:41 marcoz Exp $
 *
 * Copyright (c) 2003 Brockmann Consult GmbH. All right reserved.
 * http://www.brockmann-consult.de
 */
package org.esa.beam.bop.meris.brr;


import org.esa.beam.util.math.FractIndex;
import org.esa.beam.util.math.Interp;

public class GaseousAbsorptionCorrection implements Constants {

    private L2AuxData auxData;
    private LocalHelperVariables lh;

    public GaseousAbsorptionCorrection(L2AuxData auxData) {
        lh = new LocalHelperVariables();
        this.auxData = auxData;
    }

    /**
     * Computes the gaseous corrections for all bands of a given pixel.
     * This routine is called for every non-cloud pixel.
     * Called by {@link PixelIdentification#pixel_classification}.
     * <p/>
     * Reference: Level 2 DPM : step 2.6.12<br>
     * <p/>
     * Uses:<br>
     * {@link L2AuxData#spectral_shift_H2Owavelength}, <br>
     *
     * @param T_o3      ozone transmission for 15 bands
     * @param eta       ratio TOAR(760)/TOAR(753)
     * @param x2        ratio TOAR(900)/TOAR(885)
     * @param rhoToa    reflectance (15 bands)
     * @param detector  pixel detector id
     * @param rho_ag    gas corrected reflectance (15 bands), output
     * @param PCD_POL_F
     * @return success code (1: out or range output)
     */
    public int gas_correction(int index, double[] T_o3, double eta, double x2, float[][] rhoToa, int detector,
                              float[][] rhoNg, boolean PCD_POL_F) {
        int status = 0;
        double T_o2;  /* o2 transmission */
        double T_h2o; /* h2o transmission */
        double tg;    /* total gaseous transmission */

        for (int bandId = 0; bandId < L1_BAND_NUM; bandId++) {
            /* start with (already computed) ozone */
            T_o2 = T_h2o = 1.;
            switch (bandId) {
                case bb1:
                case bb2:
                case bb3:
                case bb4:
                case bb5:
                case bb6:
                case bb7:
                case bb8:
                case bb9:
                case bb10:
                case bb13:
                case bb14:
                    /* correct for water vapour */
                    T_h2o = trans_h2o(bandId, x2, detector); /* DPM #2.6.12.3-2 */
                    break;

                case bb11:
                    /* no correction */
                    break;
                case bb12:
                    /* correct for oxygen - v4.4 */
                    if (!PCD_POL_F) {
                        T_o2 = trans_o2(bandId, eta, detector); /* DPM #2.6.12.2-2 */
                    }

                    /* correct for water vapour */
                    T_h2o = trans_h2o(bandId, x2, detector); /* DPM #2.6.12.3-2 */
                    break;
                case bb15: /* no correction */
                    break;
            }

            tg = T_o3[bandId] * T_h2o * T_o2; /* DPM #2.6.12.4-2 */
            if (tg > 1.e-6 && tg <= 1.) {
                rhoNg[bandId][index] = (float) (rhoToa[bandId][index] / tg);              /* DPM #2.6.12.4-3 */
            } else {
                /* exception handling */
                rhoNg[bandId][index] = rhoToa[bandId][index];
                status = 1;
            }
        }  /* end loop on bands */

        return status;
    }

    /**
     * Computes o2 transmission for band {@link #bb12}.
     * Called by {@link #gas_correction}.
     * <p/>
     * Reference: Level 2 DPM, step 2.6.12.2<br>
     * Uses:
     * {@link L2AuxData#central_wavelength} <br>
     * {@link L2AuxData#spectral_shift_wavelength} <br>
     * {@link L2AuxData#O2coef}<br>
     * {@link #PPOL_NUM_SHIFT}<br>
     * {@link #O2T_POLY_K}<br>
     *
     * @param ib       band index (in case any other band is affected in the future)
     * @param R_o2     ratio rho(b11)/rho(b10)
     * @param detector detector index
     * @return o2 transmission in band ib
     */
    private double trans_o2(int ib, double R_o2, int detector) {

        double to2;

        if (ib == bb775) {

            /* DPM #2.6.12.2-3,  DPM #2.6.12.2-4, DPM #2.6.12.2-5, DPM #2.6.12.2-6 */
            Interp.interpCoord(auxData.central_wavelength[bb760][detector],
                               auxData.spectral_shift_wavelength,
                               lh.spectralShift760);

            if (lh.spectralShift760.index == (PPOL_NUM_SHIFT - 1)) {
                lh.spectralShift760.index = PPOL_NUM_SHIFT - 2;
                lh.spectralShift760.fraction = 1.;
            }

            double to2_blw = 0.;
            double to2_abv = 0.;
            to2 = 0.;

            for (int k = O2T_POLY_K - 1; k >= 0; k--) {
                /* DPM #2.6.12.3-2 */
                to2_blw = R_o2 * to2_blw + auxData.O2coef[lh.spectralShift760.index][k];
                /* DPM #2.6.12.3-2 */
                to2_abv = R_o2 * to2_abv + auxData.O2coef[lh.spectralShift760.index + 1][k];
            }

            to2 = (1. - lh.spectralShift760.fraction) * to2_blw + (lh.spectralShift760.fraction) * to2_abv;

        } else {
            to2 = 1.0;    /* DPM #2.6.12.2-3 */
        }

        return to2;
    }

    /**
     * Computes water vapour transmission for any band.
     * Called by {@link #gas_correction}.
     * <p/>
     * Reference: Level 2 DPM, step 2.6.12.3<br>
     * Uses:<br>
     * {@link L2AuxData#spectral_shift_H2Owavelength}, <br>
     * {@link L2AuxData#H2OcoefSpecShift}, <br>
     * {@link L2AuxData#H2Ocoef}, <br>
     * {@link #H2OT_POLY_K} <br>
     *
     * @param ib       band index
     * @param R_h2o    ratio rho(b15)/rho(b14)
     * @param detector detector index
     * @return h2o transmission in band ib
     */
    private double trans_h2o(int ib, double R_h2o, int detector) {
        double th2o = -999., th2o_blw = -999., th2o_abv = -999.;

        int k;

        if (ib == bb705)
            /* if (FALSE)  */ {

            /* DPM #2.1.3-1,2,3,4-b900 */
            Interp.interpCoord(auxData.central_wavelength[bb705][detector],
                               auxData.spectral_shift_H2Owavelength,
                               lh.spectralShift705);

            if (lh.spectralShift705.index == (PPOL_NUM_SHIFT - 1)) {
                lh.spectralShift705.index = PPOL_NUM_SHIFT - 2;
                lh.spectralShift705.fraction = 1.;
            }

            th2o_blw = 0.;
            th2o_abv = 0.;
            th2o = 0.;

            for (k = H2OT_POLY_K - 1; k >= 0; k--) {
                th2o_blw = R_h2o * th2o_blw + auxData.H2OcoefSpecShift[lh.spectralShift705.index][k]; /* DPM #2.6.12.3-2 */
                th2o_abv = R_h2o * th2o_abv + auxData.H2OcoefSpecShift[lh.spectralShift705.index + 1][k]; /* DPM #2.6.12.3-2 */
            }

            th2o = (1. - lh.spectralShift705.fraction) * th2o_blw + (lh.spectralShift705.fraction) * th2o_abv;
        } else {
            th2o = 0.;
            for (k = H2OT_POLY_K - 1; k >= 0; k--) {
                th2o = R_h2o * th2o + auxData.H2Ocoef[ib][k]; /* DPM #2.6.12.3-2 */
            }
        }

        return th2o;
    }

    private static class LocalHelperVariables {
        /**
         * Local helper variables used in {@link GaseousAbsorptionCorrection#trans_h2o}.
         */
        final FractIndex spectralShift760 = new FractIndex();
        final FractIndex spectralShift705 = new FractIndex();
    }
}
