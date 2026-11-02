//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                       S c a l e S t e p                                        //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
// <editor-fold defaultstate="collapsed" desc="hdr">
//
//  Copyright © Audiveris 2026. All rights reserved.
//
//  This program is free software: you can redistribute it and/or modify it under the terms of the
//  GNU Affero General Public License as published by the Free Software Foundation, either version
//  3 of the License, or (at your option) any later version.
//
//  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
//  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
//  See the GNU Affero General Public License for more details.
//
//  You should have received a copy of the GNU Affero General Public License along with this
//  program.  If not, see <http://www.gnu.org/licenses/>.
//------------------------------------------------------------------------------------------------//
// </editor-fold>
package org.audiveris.omr.step;

import org.audiveris.omr.constant.Constant;
import org.audiveris.omr.constant.ConstantSet;
import org.audiveris.omr.run.RunTable;
import org.audiveris.omr.sheet.Picture;
import org.audiveris.omr.sheet.Scale;
import org.audiveris.omr.sheet.ScaleBuilder;
import org.audiveris.omr.sheet.Sheet;
import org.audiveris.omr.sheet.ui.SheetTab;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;

/**
 * Class <code>ScaleStep</code> implements <b>SCALE</b> step, which determines the
 * general scaling informations of a sheet, based essentially on the mean distance
 * between staff lines.
 *
 * @author Hervé Bitteur
 */
public class ScaleStep
        extends AbstractStep
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Constants constants = new Constants();

    private static final Logger logger = LoggerFactory.getLogger(ScaleStep.class);

    //~ Constructors -------------------------------------------------------------------------------

    /**
     * Creates a new ScaleStep object.
     */
    public ScaleStep ()
    {
    }

    //~ Methods ------------------------------------------------------------------------------------

    //------//
    // doit //
    //------//
    @Override
    public void doit (Sheet sheet)
        throws StepException
    {
        Scale scale = new ScaleBuilder(sheet).retrieveScale();

        if (scale.getInterline() < constants.minInterline.getValue()) {
            scale = renderAgain(sheet, scale);
        }

        logger.info("{}", scale);
        sheet.setScale(scale);
    }

    private static Scale renderAgain (Sheet sheet,
                                      Scale scale)
        throws StepException
    {
        final int interline = scale.getInterline();
        final double zoom = Math.min(
                constants.targetInterline.getValue() / (double) interline,
                LoadStep.maxZoom(sheet));
        final BufferedImage image = zoom > 1 ? sheet.getStub().loadVectorImage(zoom) : null;

        if (image == null) {
            return scale;
        }

        final RunTable binary = sheet.getPicture().getVerticalTable(Picture.TableKey.BINARY);
        logger.info(
                "Interline {} too small, image rendered again {}x{}",
                interline,
                image.getWidth(),
                image.getHeight());
        sheet.setImage(image, true);
        new BinaryStep().doit(sheet);

        try {
            final Scale finer = new ScaleBuilder(sheet, false).retrieveScale();

            if (finer.getInterline() > interline) {
                return finer;
            }

            logger.info(
                    "Interline {} found again is not larger, original image kept",
                    finer.getInterline());
        } catch (StepException ex) {
            logger.info("No scale found again ({}), original image kept", ex.getMessage());
        }

        sheet.getPicture().setTable(Picture.TableKey.BINARY, binary, true);

        return scale;
    }

    //-------------//
    // getSheetTab //
    //-------------//
    @Override
    public SheetTab getSheetTab ()
    {
        return SheetTab.BINARY_TAB;
    }

    private static class Constants
            extends ConstantSet
    {
        private final Constant.Integer minInterline = new Constant.Integer(
                "Pixels",
                18,
                "Vector page interline below which the page is rendered again at a higher resolution");

        private final Constant.Integer targetInterline = new Constant.Integer(
                "Pixels",
                20,
                "Interline aimed at when a vector page is rendered again");
    }
}
