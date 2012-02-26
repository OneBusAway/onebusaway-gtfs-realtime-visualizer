function init() {

	var animation_steps = 20;
		
	/**
	 * Create a custom-styled Googe Map with no labels and custom color scheme.
	 */
	var map_style = [ {
		elementType : "labels",
		stylers : [ {
			visibility : "off"
		} ]
	}, {
		stylers : [ {
			saturation : -69
		} ]
	} ];
	var map_canvas = document.getElementById("map_canvas");
	var myOptions = {
		center : new google.maps.LatLng(42.349, -71.059),
		zoom : 8,
		styles : map_style,
		mapTypeId : google.maps.MapTypeId.ROADMAP
	};
	var map = new google.maps.Map(map_canvas, myOptions);

	/**
	 * We want to assign a random color to each bus in our visualization. We
	 * pick from the HSV color-space since it gives more natural colors.
	 */
	var HsvToRgb = function(h, s, v) {
		h_int = parseInt(h * 6);
		f = h * 6 - h_int;
		var a = v * (1 - s);
		var b = v * (1 - f * s);
		var c = v * (1 - (1 - f) * s);
		switch (h_int) {
		case 0:
			return [ v, c, a ];
		case 1:
			return [ b, v, a ];
		case 2:
			return [ a, v, c ];
		case 3:
			return [ a, b, v ];
		case 4:
			return [ c, a, v ];
		case 5:
			return [ v, a, b ];
		}
	}

	var HsvToRgbString = function(h, s, v) {
		var rgb = HsvToRgb(h, s, v);
		for ( var i = 0; i < rgb.length; ++i) {
			rgb[i] = parseInt(rgb[i] * 256)
		}
		return 'rgb(' + rgb[0] + ',' + rgb[1] + ',' + rgb[2] + ')';
	};

	var h = Math.random();
	var golden_ratio_conjugate = 0.618033988749895;

	var NextRandomColor = function() {
		h = (h + golden_ratio_conjugate) % 1;
		return HsvToRgbString(h, 0.90, 0.90)
	};

	var vehicles_by_id = {};
	
	var icon = new google.maps.MarkerImage('http://localhost:8080/WhiteCircle8.png', null, null, new google.maps.Point(4, 4));

	var updateVehicle = function(v, updates) {
		var id = v.id;
		if (!(id in vehicles_by_id)) {
			var point = new google.maps.LatLng(v.lat, v.lon);
			var path = new google.maps.MVCArray();
			path.push(point);
			var marker_opts = {
					clickable: false,
					draggable: false,
					flat: false,
					icon: icon,
					map: map,
					position: point,
			};
			var polyline_opts = {
				clickable : false,
				editable : false,
				map : map,
				path : path,
				strokeColor : NextRandomColor(),
				strokeOpacity : 0.8,
				strokeWeight : 4
			};
			vehicles_by_id[id] = {
				id: id,
				marker: new google.maps.Marker(marker_opts),
				polyline : new google.maps.Polyline(polyline_opts),
				path : path,
				lastUpdate : -1
			};
		}
		var vehicle = vehicles_by_id[id];
		if (vehicle.lastUpdate >= v.lastUpdate) {
			return;
		}
		vehicle.lastUpdate = v.lastUpdate

		var path = vehicle.path;
		var last = path.getAt(path.getLength() - 1);
		path.push(last);

		var lat_delta = (v.lat - last.lat()) / animation_steps;
		var lon_delta = (v.lon - last.lng()) / animation_steps;

		if (lat_delta != 0 && lon_delta != 0) {
			for ( var i = 0; i < animation_steps; ++i) {				
				var lat = last.lat() + lat_delta * (i + 1);
				var lon = last.lng() + lon_delta * (i + 1);
				updates[i].push(CreateUpdateStep(vehicle, lat, lon));
			}			
		}
	}

	function CreateUpdateStep(vehicle, lat, lon) {
		return function() {
			var point = new google.maps.LatLng(lat, lon);
			vehicle.marker.setPosition(point);
			var path = vehicle.path;
			var index = path.getLength() - 1;
			path.setAt(index, point);
		};
	}

	/**
	 * We create a WebSocket to listen for vehilce position updates from our
	 * webserver.
	 */
	if ("WebSocket" in window) {
		var ws = new WebSocket("ws://localhost:8080/data.json");
		ws.onopen = function() {
			console.log("WebSockets connection opened");
		}
		ws.onmessage = function(e) {
			console.log("Got WebSockets message");
			var vehicles = jQuery.parseJSON(e.data);
			var updates = [];
			for ( var i = 0; i < animation_steps; ++i) {
				updates.push(new Array());
			}
			jQuery.each(vehicles, function() {
				updateVehicle(this, updates);
			});
			console.log("Handle WebSockets message");
			var applyUpdates = function() {
				console.log("apply updates");
				if (updates.length == 0) {
					return;
				}
				var fs = updates.shift();
				for (var i=0; i<fs.length; i++) {
					fs[i]();
				}
				setTimeout(applyUpdates, 1);
			};
			setTimeout(applyUpdates, 1);
		}
		ws.onclose = function() {
			console.log("WebSockets connection closed");
		}
	} else {
		alert("No WebSockets support");
	}
}