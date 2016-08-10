package com.wdtinc.mapbox_vector_tile.adapt.jts;

import com.google.protobuf.ProtocolStringList;
import com.vividsolutions.jts.algorithm.CGAlgorithms;
import com.vividsolutions.jts.geom.*;
import com.wdtinc.mapbox_vector_tile.encoding.GeomCmd;
import com.wdtinc.mapbox_vector_tile.VectorTile;
import com.wdtinc.mapbox_vector_tile.encoding.GeomCmdHdr;
import com.wdtinc.mapbox_vector_tile.encoding.ZigZag;
import com.wdtinc.mapbox_vector_tile.util.Vec2d;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Load Mapbox Vector Tiles (MVT) to JTS {@link Geometry}. Feature tags may be converted
 * to user data via {@link ITagConverter}.
 */
public final class MvtReader {
    private static final int MIN_LINE_STRING_LEN = 6; // MoveTo,1 + LineTo,1
    private static final int MIN_POLYGON_LEN = 9; // MoveTo,1 + LineTo,2 + ClosePath


    /**
     * Convenience method for loading MVT from file.
     * See {@link #loadMvt(InputStream, GeometryFactory, ITagConverter)}.
     *
     * @param p path to the MVT
     * @param geomFactory allows for JTS geometry creation
     * @param tagConverter converts MVT feature tags to JTS user data object
     * @return JTS geometries in using MVT coordinates
     * @throws IOException failure reading MVT from path
     * @see #loadMvt(InputStream, GeometryFactory, ITagConverter)
     * @see Geometry
     * @see Geometry#getUserData()
     */
    public static List<Geometry> loadMvt(Path p, GeometryFactory geomFactory,
                                         ITagConverter tagConverter) throws IOException {
        final List<Geometry> geometries;

        try(final InputStream is = new FileInputStream(p.toFile())) {
            geometries = loadMvt(is, geomFactory, tagConverter);
        }

        return geometries;
    }

    /**
     * Load an MVT to JTS geometries using coordinates. Uses {@code tagConverter} to create user data
     * from feature properties.
     *
     * @param is stream with MVT data
     * @param geomFactory allows for JTS geometry creation
     * @param tagConverter converts MVT feature tags to JTS user data object.
     * @return JTS geometries in using MVT coordinates
     * @throws IOException failure reading MVT from stream
     * @see Geometry
     * @see Geometry#getUserData()
     */
    public static List<Geometry> loadMvt(InputStream is, GeometryFactory geomFactory,
                                         ITagConverter tagConverter) throws IOException {

        final List<Geometry> tileGeoms = new ArrayList<>();
        final VectorTile.Tile mvt = VectorTile.Tile.parseFrom(is);
        final Vec2d cursor = new Vec2d();

        for(VectorTile.Tile.Layer nextLayer : mvt.getLayersList()) {

            final ProtocolStringList keysList = nextLayer.getKeysList();
            final List<VectorTile.Tile.Value> valuesList = nextLayer.getValuesList();

            for(VectorTile.Tile.Feature nextFeature : nextLayer.getFeaturesList()) {

                final Long id = nextFeature.hasId() ? nextFeature.getId() : null;

                final VectorTile.Tile.GeomType geomType = nextFeature.getType();

                if(geomType == VectorTile.Tile.GeomType.UNKNOWN) {
                    continue;
                }

                final List<Integer> geomCmds = nextFeature.getGeometryList();
                cursor.set(0d, 0d);
                final Geometry nextGeom = readGeometry(geomCmds, geomType, geomFactory, cursor);
                if(nextGeom != null) {
                    tileGeoms.add(nextGeom);
                    nextGeom.setUserData(tagConverter.toUserData(id, nextFeature.getTagsList(), keysList, valuesList));
                }
            }
        }

        return tileGeoms;
    }

    private static Geometry readGeometry(List<Integer> geomCmds, VectorTile.Tile.GeomType geomType,
                             GeometryFactory geomFactory, Vec2d cursor) {
        Geometry result = null;

        switch(geomType) {
            case POINT:
                result = readPoints(geomFactory, geomCmds, cursor);
                break;
            case LINESTRING:
                result = readLines(geomFactory, geomCmds, cursor);
                break;
            case POLYGON:
                result = readPolys(geomFactory, geomCmds, cursor);
                break;
            default:
                LoggerFactory.getLogger(MvtReader.class).error("readGeometry(): Unhandled geometry type [{}]", geomType);
        }

        return result;
    }

    /**
     * Create {@link Point} or {@link MultiPoint} from MVT geometry drawing commands.
     *
     * @param geomFactory creates JTS geometry
     * @param geomCmds contains MVT geometry commands
     * @param cursor contains current MVT extent position
     * @return JTS geometry or null on failure
     */
    private static Geometry readPoints(GeometryFactory geomFactory, List<Integer> geomCmds, Vec2d cursor) {

        // Guard: must have header
        if(geomCmds.isEmpty()) {
            return null;
        }

        /** Geometry command index */
        int i = 0;

        // Read command header
        final int cmdHdr = geomCmds.get(i++);
        final int cmdLength = GeomCmdHdr.getCmdLength(cmdHdr);
        final GeomCmd cmd = GeomCmdHdr.getCmd(cmdHdr);

        // Guard: command type
        if(cmd != GeomCmd.MoveTo) {
            return null;
        }

        // Guard: minimum command length
        if(cmdLength < 1) {
            return null;
        }

        // Guard: header data unsupported by geometry command buffer
        //  (require header and at least 1 value * 2 params)
        if(cmdLength * GeomCmd.MoveTo.getParamCount() + 1 > geomCmds.size()) {
            return null;
        }

        final CoordinateSequence coordSeq = geomFactory.getCoordinateSequenceFactory().create(cmdLength, 2);
        int coordIndex = 0;
        Coordinate nextCoord;

        while(i < geomCmds.size() - 1) {
            cursor.add(
                    ZigZag.decode(geomCmds.get(i++)),
                    ZigZag.decode(geomCmds.get(i++))
            );

            nextCoord = coordSeq.getCoordinate(coordIndex++);
            nextCoord.setOrdinate(0, cursor.x);
            nextCoord.setOrdinate(1, cursor.y);
        }

        return coordSeq.size() == 1 ? geomFactory.createPoint(coordSeq) : geomFactory.createMultiPoint(coordSeq);
    }

    /**
     * Create {@link LineString} or {@link MultiLineString} from MVT geometry drawing commands.
     *
     * @param geomFactory creates JTS geometry
     * @param geomCmds contains MVT geometry commands
     * @param cursor contains current MVT extent position
     * @return JTS geometry or null on failure
     */
    private static Geometry readLines(GeometryFactory geomFactory, List<Integer> geomCmds, Vec2d cursor) {

        // Guard: must have header
        if(geomCmds.isEmpty()) {
            return null;
        }

        /** Geometry command index */
        int i = 0;

        int cmdHdr;
        int cmdLength;
        GeomCmd cmd;
        List<LineString> geoms = new ArrayList<>(1);
        CoordinateSequence nextCoordSeq;
        Coordinate nextCoord;

        while(i <= geomCmds.size() - MIN_LINE_STRING_LEN) {

            // --------------------------------------------
            // Expected: MoveTo command of length 1
            // --------------------------------------------

            // Read command header
            cmdHdr = geomCmds.get(i++);
            cmdLength = GeomCmdHdr.getCmdLength(cmdHdr);
            cmd = GeomCmdHdr.getCmd(cmdHdr);

            // Guard: command type and length
            if(cmd != GeomCmd.MoveTo || cmdLength != 1) {
                break;
            }

            // Update cursor position with relative move
            cursor.add(
                    ZigZag.decode(geomCmds.get(i++)),
                    ZigZag.decode(geomCmds.get(i++))
            );


            // --------------------------------------------
            // Expected: LineTo command of length > 0
            // --------------------------------------------

            // Read command header
            cmdHdr = geomCmds.get(i++);
            cmdLength = GeomCmdHdr.getCmdLength(cmdHdr);
            cmd = GeomCmdHdr.getCmd(cmdHdr);

            // Guard: command type and length
            if(cmd != GeomCmd.LineTo || cmdLength < 1) {
                break;
            }

            // Guard: header data length unsupported by geometry command buffer
            //  (require at least (1 value * 2 params) + current_index)
            if((cmdLength * GeomCmd.LineTo.getParamCount()) + i > geomCmds.size()) {
                break;
            }

            nextCoordSeq = geomFactory.getCoordinateSequenceFactory().create(1 + cmdLength, 2);

            // Set first point from MoveTo command
            nextCoord = nextCoordSeq.getCoordinate(0);
            nextCoord.setOrdinate(0, cursor.x);
            nextCoord.setOrdinate(1, cursor.y);

            // Set remaining points from LineTo command
            for(int lineToIndex = 0; lineToIndex < cmdLength; ++lineToIndex) {

                // Update cursor position with relative line delta
                cursor.add(
                        ZigZag.decode(geomCmds.get(i++)),
                        ZigZag.decode(geomCmds.get(i++))
                );

                nextCoord = nextCoordSeq.getCoordinate(lineToIndex + 1);
                nextCoord.setOrdinate(0, cursor.x);
                nextCoord.setOrdinate(1, cursor.y);
            }

            geoms.add(geomFactory.createLineString(nextCoordSeq));
        }

        return geoms.size() == 1 ? geoms.get(0) : geomFactory.createMultiLineString(geoms.toArray(new LineString[geoms.size()]));
    }

    /**
     * Create {@link Polygon} or {@link MultiPolygon} from MVT geometry drawing commands.
     *
     * @param geomFactory creates JTS geometry
     * @param geomCmds contains MVT geometry commands
     * @param cursor contains current MVT extent position
     * @return JTS geometry or null on failure
     */
    private static Geometry readPolys(GeometryFactory geomFactory, List<Integer> geomCmds, Vec2d cursor) {

        // Guard: must have header
        if(geomCmds.isEmpty()) {
            return null;
        }

        /** Geometry command index */
        int i = 0;

        int cmdHdr;
        int cmdLength;
        GeomCmd cmd;
        List<LinearRing> rings = new ArrayList<>(1);
        CoordinateSequence nextCoordSeq;
        Coordinate nextCoord;

        while(i <= geomCmds.size() - MIN_POLYGON_LEN) {

            // --------------------------------------------
            // Expected: MoveTo command of length 1
            // --------------------------------------------

            // Read command header
            cmdHdr = geomCmds.get(i++);
            cmdLength = GeomCmdHdr.getCmdLength(cmdHdr);
            cmd = GeomCmdHdr.getCmd(cmdHdr);

            // Guard: command type and length
            if(cmd != GeomCmd.MoveTo || cmdLength != 1) {
                break;
            }

            // Update cursor position with relative move
            cursor.add(
                    ZigZag.decode(geomCmds.get(i++)),
                    ZigZag.decode(geomCmds.get(i++))
            );


            // --------------------------------------------
            // Expected: LineTo command of length > 1
            // --------------------------------------------

            // Read command header
            cmdHdr = geomCmds.get(i++);
            cmdLength = GeomCmdHdr.getCmdLength(cmdHdr);
            cmd = GeomCmdHdr.getCmd(cmdHdr);

            // Guard: command type and length
            if(cmd != GeomCmd.LineTo || cmdLength < 2) {
                break;
            }

            // Guard: header data length unsupported by geometry command buffer
            //  (require at least (2 values * 2 params) + (current index 'i') + (1 for ClosePath))
            if((cmdLength * GeomCmd.LineTo.getParamCount()) + i + 1 > geomCmds.size()) {
                break;
            }

            nextCoordSeq = geomFactory.getCoordinateSequenceFactory().create(2 + cmdLength, 2);

            // Set first point from MoveTo command
            nextCoord = nextCoordSeq.getCoordinate(0);
            nextCoord.setOrdinate(0, cursor.x);
            nextCoord.setOrdinate(1, cursor.y);

            // Set remaining points from LineTo command
            for(int lineToIndex = 0; lineToIndex < cmdLength; ++lineToIndex) {

                // Update cursor position with relative line delta
                cursor.add(
                        ZigZag.decode(geomCmds.get(i++)),
                        ZigZag.decode(geomCmds.get(i++))
                );

                nextCoord = nextCoordSeq.getCoordinate(lineToIndex + 1);
                nextCoord.setOrdinate(0, cursor.x);
                nextCoord.setOrdinate(1, cursor.y);
            }


            // --------------------------------------------
            // Expected: ClosePath command of length 1
            // --------------------------------------------

            // Read command header
            cmdHdr = geomCmds.get(i++);
            cmdLength = GeomCmdHdr.getCmdLength(cmdHdr);
            cmd = GeomCmdHdr.getCmd(cmdHdr);

            if(cmd != GeomCmd.ClosePath || cmdLength != 1) {
                break;
            }

            // Set last point from ClosePath command
            nextCoord = nextCoordSeq.getCoordinate(nextCoordSeq.size() - 1);
            nextCoord.setOrdinate(0, nextCoordSeq.getOrdinate(0, 0));
            nextCoord.setOrdinate(1, nextCoordSeq.getOrdinate(0, 1));

            rings.add(geomFactory.createLinearRing(nextCoordSeq));
        }


        // Classify rings
        final List<Polygon> polygons = classifyRings(rings, geomFactory);
        if(polygons.size() < 1) {
            return null;

        } else if(polygons.size() == 1) {
            return polygons.get(0);

        } else {
            return geomFactory.createMultiPolygon(polygons.toArray(new Polygon[polygons.size()]));
        }
    }


    /**
     * <p>Classify a list of rings into polygons using surveyor formula.</p>
     *
     * <p>Zero-area polygons are removed.</p>
     *
     * @param rings linear rings to classify into polygons
     * @param geomFactory creates JTS geometry
     * @return polygons from classified rings
     * @see CGAlgorithms#signedArea(Coordinate[])
     */
    private static List<Polygon> classifyRings(List<LinearRing> rings, GeometryFactory geomFactory) {
        final List<Polygon> polygons = new ArrayList<>();
        final List<LinearRing> holes = new ArrayList<>();

        double outerArea = 0d;
        LinearRing outerPoly = null;

        for(LinearRing r : rings) {
            double area = CGAlgorithms.signedArea(r.getCoordinates());

            if(!r.isRing()) {
                continue; // sanity check, could probably be handled in a isSimple() check
            }

            if(area == 0d) {
                continue; // zero-area
            }

            if(area > 0d) {
                if(outerPoly != null) {
                    polygons.add(geomFactory.createPolygon(outerPoly, holes.toArray(new LinearRing[holes.size()])));
                    holes.clear();
                }

                // Pos --> CCW, Outer
                outerPoly = r;
                outerArea = area;

            } else {

                if(Math.abs(outerArea) < Math.abs(area)) {
                    continue; // Holes must have less area, could probably be handled in a isSimple() check
                }

                // Neg --> CW, Hole
                holes.add(r);
            }
        }

        if(outerPoly != null) {
            holes.toArray();
            polygons.add(geomFactory.createPolygon(outerPoly, holes.toArray(new LinearRing[holes.size()])));
        }

        return polygons;
    }
}