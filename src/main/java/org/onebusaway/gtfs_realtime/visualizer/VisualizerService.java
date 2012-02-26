/**
 * Copyright (C) 2012 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onebusaway.gtfs_realtime.visualizer;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.google.transit.realtime.GtfsRealtime.Position;
import com.google.transit.realtime.GtfsRealtime.VehiclePosition;

@Singleton
public class VisualizerService {

  private static final Logger _log = LoggerFactory.getLogger(VisualizerService.class);

  private URL _vehiclePositionsUrl;

  private ScheduledExecutorService _executor;

  private Map<String, Vehicle> _vehiclesById = new HashMap<String, Vehicle>();

  private List<VehicleListener> _listeners = new CopyOnWriteArrayList<VehicleListener>();

  private final RefreshTask _refreshTask = new RefreshTask();

  private int _refreshInterval = 20;

  private boolean _dynamicRefreshInterval = true;

  private long _mostRecentRefresh = -1;

  public void setVehiclePositionsUrl(URL url) {
    _vehiclePositionsUrl = url;
  }

  @PostConstruct
  public void start() {
    _executor = Executors.newSingleThreadScheduledExecutor();
    _executor.schedule(_refreshTask, 0, TimeUnit.SECONDS);
  }

  @PreDestroy
  public void stop() {
    _executor.shutdownNow();
  }

  public void addListener(VehicleListener listener) {
    _listeners.add(listener);
  }

  public void removeListener(VehicleListener listener) {
    _listeners.remove(listener);
  }

  private void refresh() throws IOException {

    _log.info("refreshing vehicle positions");

    List<Vehicle> vehicles = new ArrayList<Vehicle>();
    boolean update = false;

    FeedMessage feed = FeedMessage.parseFrom(_vehiclePositionsUrl.openStream());

    for (FeedEntity entity : feed.getEntityList()) {
      VehiclePosition vehicle = entity.getVehicle();
      if (vehicle == null) {
        continue;
      }
      if (vehicle.hasPosition()) {
        Position position = vehicle.getPosition();
        Vehicle v = new Vehicle();
        v.setId(entity.getId());
        v.setLat(position.getLatitude());
        v.setLon(position.getLongitude());
        v.setLastUpdate(System.currentTimeMillis());

        Vehicle existing = _vehiclesById.get(v.getId());
        if (existing == null || existing.getLat() != v.getLat()
            || existing.getLon() != v.getLon()) {
          _vehiclesById.put(v.getId(), v);
          update = true;
        } else {
          v.setLastUpdate(existing.getLastUpdate());
        }

        vehicles.add(v);
      }
    }

    if (update) {
      _log.info("vehicles updated: " + vehicles.size());
      if (_dynamicRefreshInterval) {
        updateRefreshInterval();
      }
    }

    for (VehicleListener listener : _listeners) {
      listener.handleVehicles(vehicles);
    }

    _executor.schedule(_refreshTask, _refreshInterval, TimeUnit.SECONDS);
  }

  private void updateRefreshInterval() {
    long t = System.currentTimeMillis();
    if (_mostRecentRefresh != -1) {
      int refreshInterval = (int) ((t - _mostRecentRefresh) / (2 * 1000));
      _refreshInterval = Math.max(10, refreshInterval);
      _log.info("refresh interval: " + _refreshInterval);
    }
    _mostRecentRefresh = t;
  }

  private class RefreshTask implements Runnable {
    @Override
    public void run() {
      try {
        refresh();
      } catch (Exception ex) {
        _log.error("error refreshing GTFS-realtime data", ex);
      }
    }
  }

}
