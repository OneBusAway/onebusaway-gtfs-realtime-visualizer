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
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Singleton;

import org.eclipse.jetty.websocket.WebSocket.Connection;
import org.eclipse.jetty.websocket.WebSocket.OnBinaryMessage;
import org.eclipse.jetty.websocket.WebSocketClient;
import org.eclipse.jetty.websocket.WebSocketClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.FeedHeader;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.google.transit.realtime.GtfsRealtime.Position;
import com.google.transit.realtime.GtfsRealtime.VehicleDescriptor;
import com.google.transit.realtime.GtfsRealtime.VehiclePosition;

@Singleton
public class VisualizerService {

  private static final Logger _log = LoggerFactory.getLogger(VisualizerService.class);

  private URI _vehiclePositionsUri;

  private ScheduledExecutorService _executor;

  private WebSocketClientFactory _webSocketFactory;

  private WebSocketClient _webSocketClient;

  private IncrementalWebSocket _incrementalWebSocket;

  private Future<Connection> _webSocketConnection;

  private Map<String, String> _vehicleIdsByEntityIds = new HashMap<String, String>();

  private Map<String, Vehicle> _vehiclesById = new ConcurrentHashMap<String, Vehicle>();

  private List<VehicleListener> _listeners = new CopyOnWriteArrayList<VehicleListener>();

  private final RefreshTask _refreshTask = new RefreshTask();

  private int _refreshInterval = 20;

  private boolean _dynamicRefreshInterval = true;

  private long _mostRecentRefresh = -1;

  public void setVehiclePositionsUri(URI uri) {
    _vehiclePositionsUri = uri;
  }

  @PostConstruct
  public void start() throws Exception {
    String scheme = _vehiclePositionsUri.getScheme();
    if (scheme.equals("ws") || scheme.equals("wss")) {
      _webSocketFactory = new WebSocketClientFactory();
      _webSocketFactory.start();
      _webSocketClient = _webSocketFactory.newWebSocketClient();
      _webSocketClient.setMaxBinaryMessageSize(16384000); 
      _incrementalWebSocket = new IncrementalWebSocket();
      _webSocketConnection = _webSocketClient.open(_vehiclePositionsUri,
          _incrementalWebSocket);
    } else {
      _executor = Executors.newSingleThreadScheduledExecutor();
      _executor.schedule(_refreshTask, 0, TimeUnit.SECONDS);
    }
  }

  @PreDestroy
  public void stop() throws Exception {
    if (_webSocketConnection != null) {
      _webSocketConnection.cancel(false);
    }
    if (_webSocketClient != null) {
      _webSocketClient = null;
    }
    if (_webSocketFactory != null) {
      _webSocketFactory.stop();
      _webSocketFactory = null;
    }
    if (_executor != null) {
      _executor.shutdownNow();
    }
  }

  public List<Vehicle> getAllVehicles() {
    return new ArrayList<Vehicle>(_vehiclesById.values());
  }

  public void addListener(VehicleListener listener) {
    _listeners.add(listener);
  }

  public void removeListener(VehicleListener listener) {
    _listeners.remove(listener);
  }

  private void refresh() throws IOException {

    _log.info("refreshing vehicle positions");

    URL url = _vehiclePositionsUri.toURL();
    FeedMessage feed = FeedMessage.parseFrom(url.openStream());

    boolean hadUpdate = processDataset(feed);

    if (hadUpdate) {
      if (_dynamicRefreshInterval) {
        updateRefreshInterval();
      }
    }

    _executor.schedule(_refreshTask, _refreshInterval, TimeUnit.SECONDS);
  }

  private boolean processDataset(FeedMessage feed) {

    List<Vehicle> vehicles = new ArrayList<Vehicle>();
    boolean update = false;

    for (FeedEntity entity : feed.getEntityList()) {
      if (entity.hasIsDeleted() && entity.getIsDeleted()) {
        String vehicleId = _vehicleIdsByEntityIds.get(entity.getId());
        if (vehicleId == null) {
          _log.warn("unknown entity id in deletion request: " + entity.getId());
          continue;
        }
        _vehiclesById.remove(vehicleId);
        continue;
      }
      if (!entity.hasVehicle()) {
        continue;
      }
      VehiclePosition vehicle = entity.getVehicle();
      String vehicleId = getVehicleId(vehicle);
      if (vehicleId == null) {
        continue;
      }
      _vehicleIdsByEntityIds.put(entity.getId(), vehicleId);
      if (!vehicle.hasPosition()) {
        continue;
      }
      Position position = vehicle.getPosition();
      Vehicle v = new Vehicle();
      v.setId(vehicleId);
      v.setLat(position.getLatitude());
      v.setLon(position.getLongitude());
      v.setLastUpdate(System.currentTimeMillis());

      Vehicle existing = _vehiclesById.get(vehicleId);
      if (existing == null || existing.getLat() != v.getLat()
          || existing.getLon() != v.getLon()) {
        _vehiclesById.put(vehicleId, v);
        update = true;
      } else {
        v.setLastUpdate(existing.getLastUpdate());
      }

      vehicles.add(v);
    }

    if (update) {
      _log.info("vehicles updated: " + vehicles.size());
    }

    for (VehicleListener listener : _listeners) {
      listener.handleVehicles(vehicles);
    }

    return update;
  }

  /**
   * @param vehicle
   * @return
   */
  private String getVehicleId(VehiclePosition vehicle) {
    if (!vehicle.hasVehicle()) {
      return null;
    }
    VehicleDescriptor desc = vehicle.getVehicle();
    if (!desc.hasId()) {
      return null;
    }
    return desc.getId();
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

  private class IncrementalWebSocket implements OnBinaryMessage {

    @Override
    public void onOpen(Connection connection) {

    }

    @Override
    public void onMessage(byte[] buf, int offset, int length) {
      if (offset != 0 || buf.length != length) {
        byte trimmed[] = new byte[length];
        System.arraycopy(buf, offset, trimmed, 0, length);
        buf = trimmed;
      }
      FeedMessage message = parseMessage(buf);
      FeedHeader header = message.getHeader();
      switch (header.getIncrementality()) {
        case FULL_DATASET:
          processDataset(message);
          break;
        case DIFFERENTIAL:
          processDataset(message);
          break;
        default:
          _log.warn("unknown incrementality: " + header.getIncrementality());
      }
    }

    @Override
    public void onClose(int closeCode, String message) {

    }

    private FeedMessage parseMessage(byte[] buf) {
      try {
        return FeedMessage.parseFrom(buf);
      } catch (InvalidProtocolBufferException ex) {
        throw new IllegalStateException(ex);
      }
    }
  }
}
