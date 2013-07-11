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
import java.io.PrintWriter;
import java.util.List;
import java.util.Set;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.util.ConcurrentHashSet;
import org.eclipse.jetty.websocket.WebSocket;
import org.eclipse.jetty.websocket.WebSocketServlet;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class DataServlet extends WebSocketServlet implements VehicleListener {

  private static final long serialVersionUID = 1L;

  private static final Logger _log = LoggerFactory.getLogger(DataServlet.class);

  private static final int WEB_SOCKET_IDLE_TIMEOUT_MS = 120 * 1000;

  private VisualizerService _visualierService;

  private Set<DataWebSocket> _sockets = new ConcurrentHashSet<DataWebSocket>();

  private volatile String _vehicles;

  @Inject
  public void setVisualizerService(VisualizerService visualizerService) {
    _visualierService = visualizerService;
  }

  @PostConstruct
  public void start() {
    _visualierService.addListener(this);
  }

  @PreDestroy
  public void stop() {
    _visualierService.removeListener(this);
  }

  @Override
  public void handleVehicles(List<Vehicle> vehicles) {
    String vehiclesAsJsonString = getVehiclesAsString(vehicles);
    for (DataWebSocket socket : _sockets) {
      socket.sendVehicles(vehiclesAsJsonString);
    }
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp)
      throws ServletException, IOException {
    resp.setContentType("application/json");
    PrintWriter writer = resp.getWriter();
    writer.write(_vehicles);
  }
  
  @Override
  public WebSocket doWebSocketConnect(HttpServletRequest request,
      String protocol) {
    return new DataWebSocket();
  }

  public void addSocket(DataWebSocket dataWebSocket) {
    String vehiclesAsJsonString = getVehiclesAsString(_visualierService.getAllVehicles());
    dataWebSocket.sendVehicles(vehiclesAsJsonString);
    _sockets.add(dataWebSocket);
  }

  public void removeSocket(DataWebSocket dataWebSocket) {
    _sockets.remove(dataWebSocket);
  }

  private String getVehiclesAsString(List<Vehicle> vehicles) {
    try {
      JSONArray array = new JSONArray();
      for (Vehicle vehicle : vehicles) {
        JSONObject obj = new JSONObject();
        obj.put("id", vehicle.getId());
        obj.put("lat", vehicle.getLat());
        obj.put("lon", vehicle.getLon());
        obj.put("lastUpdate", vehicle.getLastUpdate());
        array.put(obj);
      }
      return array.toString();
    } catch (JSONException ex) {
      throw new IllegalStateException(ex);
    }
  }

  class DataWebSocket implements WebSocket {

    private Connection _connection;

    @Override
    public void onOpen(Connection connection) {
      _connection = connection;
      _connection.setMaxIdleTime(WEB_SOCKET_IDLE_TIMEOUT_MS);
      addSocket(this);
    }

    @Override
    public void onClose(int closeCode, String message) {
      removeSocket(this);
    }

    public void sendVehicles(String vehiclesAsJsonString) {
      try {
        _connection.sendMessage(vehiclesAsJsonString);
      } catch (IOException ex) {
        _log.warn("error sending WebSocket message", ex);
      }
    }
  }
}
