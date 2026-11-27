# Placeholder geofence data (ND and MN)

`placeholder-nd-mn.txt` is a coarse, hand-simplified, **non-authoritative** stand-in so the upload geofence can be built
and tested. It was written from rough knowledge of the state outlines, not from a data source.

TODO before any release:

1. Download US Census cartographic boundary data for North Dakota and Minnesota (`cb_2023_us_state_20m` or `cb_2023_us_state_500k`).
2. Simplify to about 100 m tolerance (for example mapshaper `-simplify` with a 100 m tolerance, Douglas-Peucker).
3. Write the result in the same text format (`polygon <region>` then `lat lon` lines; `nd` and `mn` regions; multipolygons as several `polygon` blocks).
4. Replace `placeholder-nd-mn.txt`, rename `PlaceholderGeofence` to match, and keep the unit tests' sanity points (Fargo, Moorhead, Minneapolis, Bismarck inside; Sioux Falls, Billings, Winnipeg outside).
5. Confirm the 2 km buffer rule and the shared Red River boundary behaviour with Prizm.
