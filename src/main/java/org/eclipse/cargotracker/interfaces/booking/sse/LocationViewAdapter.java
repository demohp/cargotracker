package org.eclipse.cargotracker.interfaces.booking.sse;

import jakarta.enterprise.inject.spi.CDI;
import org.eclipse.cargotracker.domain.model.location.Location;
import org.eclipse.cargotracker.interfaces.Coordinates;
import org.eclipse.cargotracker.interfaces.CoordinatesFactory;

/** View adapter for displaying a location in a real-time tracking context. */
public class LocationViewAdapter {

  private final Location location;

  public LocationViewAdapter(Location location) {
    this.location = location;
  }

  public String getUnLocode() {
    return location.getUnLocode().getIdString();
  }

  public String getName() {
    return location.getName();
  }

  public Coordinates getCoordinates() {
    // CoordinatesFactory is now a CDI ApplicationScoped bean (cr-java-0067).
    // Obtain the managed instance via CDI.current() since LocationViewAdapter
    // is a plain object (not a CDI bean) instantiated by RealtimeCargoTrackingViewAdapter.
    CoordinatesFactory coordinatesFactory =
        CDI.current().select(CoordinatesFactory.class).get();
    return coordinatesFactory.find(location);
  }
}
