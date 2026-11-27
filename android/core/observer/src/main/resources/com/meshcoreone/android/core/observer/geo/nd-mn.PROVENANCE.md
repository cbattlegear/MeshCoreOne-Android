# nd-mn.json provenance

`nd-mn.json` is a byte-for-byte copy of `cmd/ingestor/fences/nd-mn.json` from the Prizm server repository
(prizm-scope, branch `mobile-observer`, copied 2026-11-27). The Prizm ingestor drops any record outside these outlines plus
2 km, so the app uses the same file and the same 2 km buffer to make the same decision and not upload what the server would drop.

- Source data: US Census Bureau cartographic boundary file `cb_2023_us_state_5m`, simplified (Douglas-Peucker, 0.5 km).
- Format: `{"source": "...", "polygons": {"Minnesota": [[lat, lon], ...], "North Dakota": [[lat, lon], ...]}}`; one ring per state,
  the closing edge is implied. Parsed by `GeofenceJson`, evaluated by `PolygonGeofence` (inside, or within 2 km of an edge).
- Size: about 11 KB (454 Minnesota and 106 North Dakota vertices), small enough to ship as an app resource.
- To update: copy the server's file over this one unchanged; the sanity tests in `NdMnGeofenceTest` must still pass.
