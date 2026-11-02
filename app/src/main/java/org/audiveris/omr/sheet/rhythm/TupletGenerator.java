//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                  T u p l e t G e n e r a t o r                                 //
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
package org.audiveris.omr.sheet.rhythm;

import org.audiveris.omr.glyph.Shape;
import org.audiveris.omr.math.GeoUtil;
import org.audiveris.omr.math.Rational;
import static org.audiveris.omr.math.Rational.TWO_OVER_THREE;
import org.audiveris.omr.sheet.Scale;
import org.audiveris.omr.sheet.Staff;
import org.audiveris.omr.sheet.SystemInfo;
import org.audiveris.omr.sig.SIGraph;
import org.audiveris.omr.sig.inter.AbstractChordInter;
import org.audiveris.omr.sig.inter.BeamGroupInter;
import org.audiveris.omr.sig.inter.Inters;
import org.audiveris.omr.sig.inter.TupletInter;
import org.audiveris.omr.sig.relation.ChordTupletRelation;
import org.audiveris.omr.sig.relation.Relation;
import org.audiveris.omr.ui.symbol.FontSymbol;
import org.audiveris.omr.ui.symbol.MusicFamily;
import org.audiveris.omr.util.Entities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import static java.util.stream.Collectors.toList;

/**
 * Class <code>TupletGenerator</code> proposes implicit tuplet signs for a measure.
 *
 * @author Hervé Bitteur
 */
public class TupletGenerator
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Logger logger = LoggerFactory.getLogger(TupletGenerator.class);

    private static final int MAX_FAMILIES = 5;

    //~ Instance fields ----------------------------------------------------------------------------

    /** Underlying measure. */
    private final Measure measure;

    private final SystemInfo system;

    private final Scale scale;

    //~ Constructors -------------------------------------------------------------------------------

    /**
     * Create a <code>TupletGenerator</code> object.
     *
     * @param measure the underlying measure
     */
    public TupletGenerator (Measure measure)
    {
        this.measure = measure;

        system = measure.getPart().getSystem();
        scale = system.getSheet().getScale();
    }

    //~ Methods ------------------------------------------------------------------------------------

    //----------//
    // getSpans //
    //----------//
    public List<List<AbstractChordInter>> getSpans ()
    {
        final List<List<AbstractChordInter>> spans = new ArrayList<>();
        final Set<AbstractChordInter> taken = new HashSet<>();

        for (BeamGroupInter group : measure.getBeamGroups()) {
            final List<AbstractChordInter> chords = group.getChords();

            if (group.getMeasures().size() > 1) {
                continue;
            }

            final Staff staff = chords.get(0).getStaff();
            final int firstX = chords.get(0).getCenter().x;
            final int lastX = chords.get(chords.size() - 1).getCenter().x;
            final List<Integer> beamed = measure.getStandardChords().stream().filter(
                    ch -> (ch.getStaff() == staff) && (ch.getBeamGroup() != null)).map(
                            ch -> ch.getCenter().x).collect(toList());
            final List<AbstractChordInter> before = getUnbeamed(
                    staff,
                    beamed.stream().filter(x -> x < firstX).max(Integer::compare).orElse(
                            Integer.MIN_VALUE),
                    firstX);
            final List<AbstractChordInter> after = getUnbeamed(
                    staff,
                    lastX,
                    beamed.stream().filter(x -> x > lastX).min(Integer::compare).orElse(
                            Integer.MAX_VALUE));

            Search:
            for (int added = 0; added <= (before.size() + after.size()); added++) {
                for (int b = Math.min(added, before.size()); b >= 0; b--) {
                    if ((added - b) > after.size()) {
                        break;
                    }

                    final List<AbstractChordInter> span = join(
                            join(before.subList(before.size() - b, before.size()), chords),
                            after.subList(0, added - b));

                    if (isTupletSpan(span) && Collections.disjoint(span, taken)) {
                        spans.add(span);
                        taken.addAll(span);

                        break Search;
                    }
                }
            }
        }

        return spans;
    }

    //---------------//
    // getHypotheses //
    //---------------//
    public List<List<List<AbstractChordInter>>> getHypotheses ()
    {
        final Map<Family, List<List<AbstractChordInter>>> families = new LinkedHashMap<>();

        for (List<AbstractChordInter> span : getSpans()) {
            if (span.stream().anyMatch(AbstractChordInter::hasTuplet)) {
                continue;
            }

            families.computeIfAbsent(
                    new Family(span.get(0).getStaff(), span.size(), getRawDuration(span)),
                    f -> new ArrayList<>()).add(span);
        }

        final List<List<List<AbstractChordInter>>> spansByFamily = new ArrayList<>(
                families.values());
        final int count = spansByFamily.size();
        final List<List<List<AbstractChordInter>>> hypotheses = new ArrayList<>();

        if (count > MAX_FAMILIES) {
            hypotheses.add(spansByFamily.stream().flatMap(List::stream).collect(toList()));

            return hypotheses;
        }

        for (int mask = 1; mask < (1 << count); mask++) {
            final List<List<AbstractChordInter>> hypothesis = new ArrayList<>();

            for (int i = 0; i < count; i++) {
                if ((mask & (1 << i)) != 0) {
                    hypothesis.addAll(spansByFamily.get(i));
                }
            }

            hypotheses.add(hypothesis);
        }

        hypotheses.sort(Comparator.comparingInt(List::size));

        return hypotheses;
    }

    //----------------//
    // getRawDuration //
    //----------------//
    private static Rational getRawDuration (List<AbstractChordInter> chords)
    {
        Rational raw = Rational.ZERO;

        for (AbstractChordInter chord : chords) {
            final Rational duration = chord.getDurationSansTuplet();

            if (duration == null) {
                return null;
            }

            raw = raw.plus(duration);
        }

        return raw;
    }

    //-------------//
    // getUnbeamed //
    //-------------//
    private List<AbstractChordInter> getUnbeamed (Staff staff,
                                                  int left,
                                                  int right)
    {
        return measure.getStandardChords().stream().filter(
                ch -> (ch.getStaff() == staff) && (ch.getBeamGroup() == null)
                        && (left < ch.getCenter().x) && (ch.getCenter().x < right)).sorted(
                                Inters.byCenterAbscissa).collect(toList());
    }

    //--------------//
    // isTupletSpan //
    //--------------//
    private static boolean isTupletSpan (List<AbstractChordInter> chords)
    {
        final Rational raw = getRawDuration(chords);

        if (raw == null) {
            return false;
        }

        final Rational actual = raw.times(TWO_OVER_THREE);

        return (actual.num == 1) && (Integer.bitCount(actual.den) == 1);
    }

    //------//
    // join //
    //------//
    private static List<AbstractChordInter> join (List<AbstractChordInter> one,
                                                  List<AbstractChordInter> two)
    {
        final List<AbstractChordInter> joined = new ArrayList<>(one);
        joined.addAll(two);

        return joined;
    }

    //------------//
    // dumpVoices //
    //------------//
    /**
     * Dump measure voices, except the whole rest voices.
     */
    private void dumpVoices ()
    {
        logger.info("{}", measure);

        final List<Voice> voices = new ArrayList<>(measure.getVoices());
        Collections.sort(voices, Voices.byId);

        for (Voice voice : voices) {
            Rational end = null;

            for (AbstractChordInter ch : voice.getChords()) {
                final Rational chDur = ch.getDuration();
                end = (end == null) ? chDur : end.plus(chDur);
            }

            logger.info("   {} duration:{}", voice, end);
        }
    }

    //-------------------//
    // generateOneTuplet //
    //-------------------//
    /**
     * Generate one implicit tuplet for the provided group of chords.
     * <p>
     * If the chords are already linked to a tuplet, this existing tuplet is returned.
     * <p>
     * We use the approximate number of notes in the provided beat group to choose between
     * TUPLET_THREE and TUPLET_SIX for tuplet shape.
     * <p>
     * Working assumption: If a chord of a beam group is implied, link the whole beam group,
     * including the interleaved rests.
     *
     * @param group the group of chords
     * @return the generated implicit tuplet
     */
    public TupletInter generateOneTuplet (List<AbstractChordInter> group)
    {
        final SIGraph sig = system.getSig();

        // Existing tuplet?
        for (AbstractChordInter ch : group) {
            for (Relation rel : sig.getRelations(ch, ChordTupletRelation.class)) {
                return (TupletInter) sig.getOppositeInter(ch, rel);
            }
        }

        // Simplistic approach
        final int size = group.size();
        final int nb = (int) Math.rint(size / 3.0);
        final Shape shape = (nb == 1) ? Shape.TUPLET_THREE : Shape.TUPLET_SIX;

        final TupletInter tuplet = new TupletInter(null, shape, 1.0);
        tuplet.setImplicit(true);

        // Precise tuplet bounds, above or below group
        tuplet.setBounds(inferTupletBounds(group, shape));

        sig.addVertex(tuplet);
        tuplet.setAbnormal(false);
        measure.addInter(tuplet);

        // Set relation between tuplet and every chord in group
        for (AbstractChordInter ch : group) {
            sig.addEdge(ch, tuplet, new ChordTupletRelation(shape));
        }

        return tuplet;
    }

    //-------------------//
    // inferTupletBounds //
    //-------------------//
    /**
     * Determine acceptable location and dimension for an implicit tuplet that would
     * apply to the provided group of chords.
     *
     * @param group the provided group of chords
     * @param shape the tuplet shape
     * @return the precise tuplet bounds
     */
    private Rectangle inferTupletBounds (List<AbstractChordInter> group,
                                         Shape shape)
    {
        // Tuplet dimension
        final MusicFamily family = system.getSheet().getStub().getMusicFamily();
        final FontSymbol fs = shape.getFontSymbolByInterline(family, scale.getInterline());
        final Dimension dim = fs.getDimension();

        // Vertical direction from group to tuplet, based on group tails side
        final TreeMap<Integer, List<AbstractChordInter>> dirs = new TreeMap<>();

        for (AbstractChordInter ch : group) {
            final int dir = ch.getStemDir();

            if (dir != 0) {
                List<AbstractChordInter> chords = dirs.get(dir);

                if (chords == null) {
                    dirs.put(dir, chords = new ArrayList<>());
                }

                chords.add(ch);
            }
        }

        int dir = 0;
        int bestCount = 0;

        for (Map.Entry<Integer, List<AbstractChordInter>> entry : dirs.entrySet()) {
            final int count = entry.getValue().size();

            if ((dir == 0) || (bestCount < count)) {
                dir = entry.getKey();
                bestCount = count;
            }
        }

        if (dir == 0) {
            dir = -1; // Safer
        }

        // Tuplet bounds
        final Rectangle box = Entities.getBounds(group);
        final Point center = GeoUtil.center(box);
        final int margin = dim.height / 10; // Small vertical margin between chord tail and tuplet
        final Rectangle tupletBox = new Rectangle(
                center.x - (dim.width / 2),
                center.y + (dir * ((box.height / 2) + margin + ((dir < 0) ? dim.height : 0))),
                dim.width,
                dim.height);

        return tupletBox;
    }

    //~ Inner Classes ------------------------------------------------------------------------------

    //--------//
    // Family //
    //--------//
    private record Family(Staff staff,
                          int size,
                          Rational rawDuration)
    {
    }
}
