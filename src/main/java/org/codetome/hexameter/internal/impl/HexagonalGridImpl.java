package org.codetome.hexameter.internal.impl;

import static org.codetome.hexameter.api.AxialCoordinate.fromCoordinates;
import static org.codetome.hexameter.api.CoordinateConverter.convertOffsetCoordinatesToAxialX;
import static org.codetome.hexameter.api.CoordinateConverter.convertOffsetCoordinatesToAxialZ;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.codetome.hexameter.api.AxialCoordinate;
import org.codetome.hexameter.api.Hexagon;
import org.codetome.hexameter.api.HexagonalGrid;
import org.codetome.hexameter.api.HexagonalGridBuilder;
import org.codetome.hexameter.api.Point;
import org.codetome.hexameter.api.exception.HexagonNotFoundException;
import org.codetome.hexameter.internal.SharedHexagonData;
import org.codetome.hexameter.internal.impl.layoutstrategy.GridLayoutStrategy;

public final class HexagonalGridImpl implements HexagonalGrid {

    private static final int[][] NEIGHBORS = { { +1, 0 }, { +1, -1 },
            { 0, -1 }, { -1, 0 }, { -1, +1 }, { 0, +1 } };
    private static final int NEIGHBOR_X_INDEX = 0;
    private static final int NEIGHBOR_Z_INDEX = 1;

    private final GridLayoutStrategy gridLayoutStrategy;
    private final SharedHexagonData sharedHexagonData;
    private final Map<String, Hexagon> hexagonStorage;

    public HexagonalGridImpl(final HexagonalGridBuilder builder) {
        sharedHexagonData = builder.getSharedHexagonData();
        gridLayoutStrategy = builder.getGridLayoutStrategy();
        if (builder.getCustomStorage() != null) {
            hexagonStorage = builder.getCustomStorage();
        } else {
            hexagonStorage = new ConcurrentHashMap<> ();
        }
        hexagonStorage.putAll(gridLayoutStrategy.createHexagons(builder));
    }

    @Override
    public Map<String, Hexagon> getHexagons() {
        return hexagonStorage;
    }

    @Override
    public Map<String, Hexagon> getHexagonsByAxialRange(final AxialCoordinate from, final AxialCoordinate to) {
        final Map<String, Hexagon> range = new HashMap<> ();
        for (int gridZ = from.getGridZ(); gridZ <= to.getGridZ(); gridZ++) {
            for (int gridX = from.getGridX(); gridX <= to.getGridX(); gridX++) {
                final AxialCoordinate currentCoordinate = fromCoordinates(gridX, gridZ);
                range.put(currentCoordinate.toKey(), getByAxialCoordinate(currentCoordinate));
            }
        }
        return range;
    }

    @Override
    public Map<String, Hexagon> getHexagonsByOffsetRange(final int gridXFrom, final int gridXTo, final int gridYFrom, final int gridYTo) {
        final Map<String, Hexagon> range = new HashMap<> ();
        for (int gridY = gridYFrom; gridY <= gridYTo; gridY++) {
            for (int gridX = gridXFrom; gridX <= gridXTo; gridX++) {
                final int axialX = convertOffsetCoordinatesToAxialX(gridX, gridY,
                        sharedHexagonData.getOrientation());
                final int axialZ = convertOffsetCoordinatesToAxialZ(gridX, gridY,
                        sharedHexagonData.getOrientation());
                final AxialCoordinate axialCoordinate = fromCoordinates(axialX, axialZ);
                range.put(axialCoordinate.toKey(), getByAxialCoordinate(axialCoordinate));
            }
        }
        return range;
    }

    @Override
    public Hexagon addHexagon(final AxialCoordinate coordinate) {
        final Hexagon newHex = new HexagonImpl(sharedHexagonData, coordinate);
        hexagonStorage.put(coordinate.toKey(), newHex);
        return newHex;
    }

    @Override
    public Hexagon removeHexagon(final AxialCoordinate coordinate) {
        checkCoordinate(coordinate);
        return hexagonStorage.remove(coordinate.toKey());
    }

    @Override
    public boolean containsAxialCoordinate(final AxialCoordinate coordinate) {
        return hexagonStorage
                .containsKey(coordinate.toKey());
    }

    @Override
    public Hexagon getByAxialCoordinate(final AxialCoordinate coordinate) {
        checkCoordinate(coordinate);
        return hexagonStorage.get(coordinate.toKey());
    }

    private void checkCoordinate(final AxialCoordinate coordinate) {
        if (!containsAxialCoordinate(coordinate)) {
            throw new HexagonNotFoundException(
                    "Coordinate is off the grid: " + coordinate.toKey());
        }
    }

    @Override
    public Hexagon getByPixelCoordinate(final double x, final double y) {
        int estimatedGridX = (int) (x / sharedHexagonData.getWidth());
        int estimatedGridZ = (int) (y / sharedHexagonData.getHeight());
        estimatedGridX = convertOffsetCoordinatesToAxialX(estimatedGridX,
                estimatedGridZ, sharedHexagonData.getOrientation());
        estimatedGridZ = convertOffsetCoordinatesToAxialZ(estimatedGridX,
                estimatedGridZ, sharedHexagonData.getOrientation());
        // it is possible that the estimated coordinates are off the grid so we
        // create a virtual hexagon
        final AxialCoordinate estimatedCoordinate = fromCoordinates(estimatedGridX, estimatedGridZ);
        final Hexagon tempHex = new HexagonImpl(sharedHexagonData, estimatedCoordinate);
        final Hexagon trueHex = refineHexagonByPixel(tempHex, x, y);
        if (hexagonsAreAtTheSamePosition(tempHex, trueHex)) {
            return getByAxialCoordinate(estimatedCoordinate);
        } else {
            return trueHex;
        }
    }

    @Override
    public Set<Hexagon> getNeighborsOf(final Hexagon hexagon) {
        final Set<Hexagon> neighbors = new HashSet<> ();
        for (final int[] neighbor : NEIGHBORS) {
            Hexagon retHex = null;
            final int neighborGridX = hexagon.getGridX() + neighbor[NEIGHBOR_X_INDEX];
            final int neighborGridZ = hexagon.getGridZ() + neighbor[NEIGHBOR_Z_INDEX];
            final AxialCoordinate neighborCoordinate = fromCoordinates(neighborGridX, neighborGridZ);
            if (containsAxialCoordinate(neighborCoordinate)) {
                retHex = getByAxialCoordinate(neighborCoordinate);
                neighbors.add(retHex);
            }
        }
        return neighbors;
    }

    private boolean hexagonsAreAtTheSamePosition(final Hexagon hex0, final Hexagon hex1) {
        return hex0.getGridX() == hex1.getGridX()
                && hex0.getGridZ() == hex1.getGridZ();
    }

    private Hexagon refineHexagonByPixel(final Hexagon hexagon, final double x, final double y) {
        Hexagon refined = hexagon;
        final Point clickedPoint = new Point(x, y);
        double smallestDistance = Point.distance(clickedPoint, new Point(
                refined.getCenterX(), refined.getCenterY()));
        for (final Hexagon neighbor : getNeighborsOf(hexagon)) {
            final double currentDistance = Point.distance(clickedPoint, new Point(
                    neighbor.getCenterX(), neighbor.getCenterY()));
            if (currentDistance < smallestDistance) {
                refined = neighbor;
                smallestDistance = currentDistance;
            }
        }
        return refined;
    }

    @Override
    public void clearSatelliteData() {
        for (final String key : hexagonStorage.keySet()) {
            hexagonStorage.get(key).setSatelliteData(null);
        }
    }

}