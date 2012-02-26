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

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VisualizerServer {

  private static final Logger _log = LoggerFactory.getLogger(VisualizerServer.class);

  private DataServlet _dataServlet;

  private int _port = 8080;

  private Server _server;

  @Inject
  public void setDataServlet(DataServlet dataServlet) {
    _dataServlet = dataServlet;
  }

  public void setPort(int port) {
    _port = port;
  }

  @PostConstruct
  public void start() throws Exception {
    _server = new Server(_port);

    ResourceHandler resourceHandler = new ResourceHandler();
    resourceHandler.setWelcomeFiles(new String[] {"index.html"});
    resourceHandler.setBaseResource(Resource.newClassPathResource("org/onebusaway/gtfs_realtime/visualizer"));

    ServletHandler servletHandler = new ServletHandler();
    servletHandler.addServletWithMapping(new ServletHolder(_dataServlet),
        "/data.json");

    HandlerList handlers = new HandlerList();
    handlers.setHandlers(new Handler[] {resourceHandler, servletHandler});

    _server.setHandler(handlers);

    _server.start();

    StringBuilder b = new StringBuilder();
    b.append("\n");
    b.append("=======================================================\n");
    b.append("\n");
    b.append("  The GTFS-realtime visualizer server has\n");
    b.append("  started.  Check it out at:\n");
    b.append("\n");
    b.append("    http://localhost:" + _port + "/\n");
    b.append("\n");
    b.append("=======================================================\n");

    _log.info(b.toString());
  }

  @PreDestroy
  public void stop() throws Exception {
    _server.stop();
  }
}
