//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                 B a s i c T i m e B u i l d e r                                //
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
package org.audiveris.omr.sheet.time;

import org.audiveris.omr.classifier.Evaluation;
import org.audiveris.omr.classifier.ShapeClassifier;
import org.audiveris.omr.glyph.Glyph;
import org.audiveris.omr.glyph.GlyphFactory;
import org.audiveris.omr.glyph.Grades;
import org.audiveris.omr.run.Orientation;
import org.audiveris.omr.run.RunTableFactory;
import org.audiveris.omr.sheet.Picture;
import org.audiveris.omr.sheet.Staff;
import org.audiveris.omr.sig.inter.Inter;
import org.audiveris.omr.sig.inter.Inters;
import org.audiveris.omr.sig.inter.NumberInter;
import org.audiveris.omr.sig.inter.TimeNumberInter;
import org.audiveris.omr.sig.inter.TimeWholeInter;
import org.audiveris.omr.util.VerticalSide;

import ij.process.Blitter;
import ij.process.ByteProcessor;

import java.awt.Rectangle;
import java.util.Iterator;
import java.util.List;

/**
 * A subclass of TimeBuilder specifically meant for extraction outside system header,
 * further down in the system measures.
 * <p>
 * Symbol extraction has already been performed, so time-signature shaped symbols are now
 * checked for consistency across all staves of the containing system.
 *
 * @author Hervé Bitteur
 */
public class BasicTimeBuilder
        extends TimeBuilder
{
    //~ Constructors -------------------------------------------------------------------------------

    /**
     * Creates a new <code>BasicTimeBuilder</code> object.
     *
     * @param staff  underlying staff
     * @param column containing time column
     */
    public BasicTimeBuilder (Staff staff,
                             BasicTimeColumn column)
    {
        super(staff, column);
    }

    //~ Methods ------------------------------------------------------------------------------------

    @Override
    public void cleanup ()
    {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    protected void findCandidates ()
    {
        // For time symbols found (whole or half), pitch is correct, but abscissa is random
        // For now, at staff level, we can only check that nums & dens are x-compatible
        BasicTimeColumn basicColumn = (BasicTimeColumn) column;

        for (Inter inter : basicColumn.timeSet) {
            if (inter.getStaff() == staff) {
                if (inter instanceof TimeWholeInter) {
                    wholes.add(inter);
                } else if (inter instanceof TimeNumberInter) {
                    TimeNumberInter number = (TimeNumberInter) inter;
                    VerticalSide side = number.getSide();

                    if (side == VerticalSide.TOP) {
                        nums.add(inter);
                    } else {
                        dens.add(inter);
                    }
                }
            }
        }

        final Rectangle columnBox = Inters.getBounds(basicColumn.timeSet);

        if (columnBox.width <= params.maxTimeWidth) {
            final int top = staff.getFirstLine().yAt(columnBox.x);
            final int mid = staff.getMidLine().yAt(columnBox.x);
            final int bottom = staff.getLastLine().yAt(columnBox.x);
            addHalf(basicColumn, new Rectangle(columnBox.x, top, columnBox.width, mid - top));
            addHalf(basicColumn, new Rectangle(columnBox.x, mid, columnBox.width, bottom - mid));
        }
    }

    private void addHalf (BasicTimeColumn basicColumn,
                          Rectangle rect)
    {
        rect.grow(0, -params.yMargin);

        final ByteProcessor source = system.getSheet().getPicture().getSource(
                Picture.SourceKey.NO_STAFF);
        final ByteProcessor buf = new ByteProcessor(rect.width, rect.height);
        buf.copyBits(source, -rect.x, -rect.y, Blitter.COPY);

        final List<Glyph> parts = GlyphFactory.buildGlyphs(
                new RunTableFactory(Orientation.VERTICAL).createTable(buf),
                rect.getLocation());
        parts.removeIf(part -> part.getWeight() < params.minPartWeight);

        if (parts.isEmpty()) {
            return;
        }

        final Glyph glyph = system.registerGlyph(GlyphFactory.buildGlyph(parts), null);

        if (glyph.getWeight() < params.minHalfTimeWeight) {
            return;
        }

        final Evaluation[] evals = ShapeClassifier.getInstance().evaluate(
                glyph,
                staff.getSpecificInterline(),
                params.maxEvalRank,
                Grades.timeMinGrade / Grades.intrinsicRatio,
                null);

        for (Evaluation eval : evals) {
            if (halfShapes.contains(eval.shape)) {
                final TimeNumberInter inter = TimeNumberInter.create(
                        glyph,
                        eval.shape,
                        Grades.intrinsicRatio * eval.grade,
                        staff);

                if (inter != null) {
                    for (Iterator<Inter> it = basicColumn.timeSet.iterator(); it.hasNext();) {
                        final Inter other = it.next();

                        if ((other instanceof NumberInter) && (other.getStaff() == staff)
                                && other.overlaps(inter)) {
                            other.remove();
                            it.remove();
                        }
                    }

                    sig.addVertex(inter);
                    basicColumn.timeSet.add(inter);
                    (inter.getSide() == VerticalSide.TOP ? nums : dens).add(inter);
                }

                return;
            }
        }
    }
}
