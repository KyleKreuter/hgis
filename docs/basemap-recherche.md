# Recherche: Kachel-Dienste der deutschen Landesvermessungen

Jede URL in diesem Dokument wurde mit einem echten HTTP-Abruf geprüft: Status,
`Content-Type`, Größe und ein Blick auf die heruntergeladene Kachel. Belegbilder
liegen unter `/tmp/basemap-probe/*.png` bzw. `*.jpg` auf der Maschine, auf der
diese Recherche lief (nicht Teil des Commits). Verwendeter User-Agent:
`hgis-basemap-probe/1.0`.

## Wichtiger Befund vorab: zwei Klassen von Diensten

Der Vertrag verlangt `urlTemplate` als "XYZ oder WMTS-KVP, mit `{z}`, `{x}`,
`{y}`". Von den unten gefundenen Diensten erfüllen das nur wenige direkt.
Drei technisch unterschiedliche Fälle kamen vor, klar getrennt gehalten:

1. **Echtes globales WMTS-Gitter** (`GoogleMapsCompatible`, Ursprung
   `-20037508.34/20037508.34`, 256-px-Kacheln, Kennung == Zoomstufe). Genau wie
   `basemap.de` im Vertragsbeispiel. Direkt als `{z}/{x}/{y}` einsetzbar.
2. **WMTS mit eigenem, nicht-globalem Gitter** (eigener Ursprung und/oder eine
   um einen festen Betrag verschobene Zoomzählung). Kacheln existieren und
   sind echt, aber `{z}/{x}/{y}` aus MapLibre passt nicht ohne Umrechnung
   direkt auf `TileMatrix`/`TileRow`/`TileCol`.
3. **Nur WMS, kein WMTS.** Funktioniert als Kachelquelle nur über den
   `{bbox-epsg-3857}`-Platzhalter, den MapLibre GL für WMS-Hintergründe
   dokumentiert (raster-Source mit BBOX-Templating statt Z/X/Y-Templating).
   Das ist eine etablierte, aber andere Mechanik als die fünf heutigen
   Einträge in `basemap.ts` nutzen. Ob das für dieses Projekt in Frage kommt,
   ist eine Entscheidung des Teams, keine von mir getroffene Annahme.

Fall 1 ist unten mit **"sofort einsetzbar"** markiert, Fall 2 mit **"eigenes
Gitter"**, Fall 3 mit **"nur WMS"**.

**Nachtrag:** Der Vertrag wurde inzwischen um Fall 3 (`{bbox-epsg-3857}`)
erweitert, Fall 2 bleibt draußen. Für die vier Länder, die zuerst nur als
Fall 2 dokumentiert waren (Nordrhein-Westfalen, Brandenburg-DOP, Sachsen,
Mecklenburg-Vorpommern), habe ich deshalb zusätzlich nach einem WMS-Zwilling
desselben Datensatzes gesucht -- die Landesvermessungen betreiben WMTS und
WMS fast immer aus demselben MapProxy/derselben Datenbasis. Für alle vier
wurde ich fündig; die Ergebnisse stehen direkt in den jeweiligen
Länderabschnitten unten, zusätzlich zum ursprünglich dokumentierten Fall-2-
WMTS (der bleibt stehen, falls das Team die Entscheidung zu Fall 2 noch
einmal überdenkt).

Zweiter Befund: der Vertrag kennt bei `coverage` nur `"DE"`, `"HH"`, `"EU"`,
`"world"`. Für zwölf weitere Bundesländer fehlt ein Code. Das ist keine
Kleinigkeit, die ich stillschweigend gelöst habe -- die Namensgebung
(`"BB"`, `"NW"`, `"BY"`, ...) muss das Team festlegen.

---

## Hamburg

### Geobasiskarten (farbig) -- nur WMS

- **Anzeigename:** "Geobasiskarten Hamburg (farbig)"
- **Gruppe:** `Bundesländer`
- **Dienst:** `https://geodienste.hamburg.de/HH_WMS_Geobasiskarten` (WMS 1.3.0)
- **Layer:** `geobasiskarten_farbig`
- **urlTemplate (Fall 3, nur WMS):**
  `https://geodienste.hamburg.de/HH_WMS_Geobasiskarten?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=geobasiskarten_farbig&STYLES=&CRS=EPSG:3857&BBOX={bbox-epsg-3857}&WIDTH=256&HEIGHT=256&FORMAT=image/png`
- **Beleg:** GetMap für Kachel z=12 (Innenstadt/Meßberg), Status 200,
  `image/png`, 53 KB. Bild zeigt eine saubere Stadtkarte ohne
  Wasserzeichen.
- **WMTS geprüft und nicht gefunden:** `HH_WMTS_Geobasiskarten`,
  `wmts_geobasiskarten[_farbig]`, `wmts` -- alle 404.
- **Zoom/Abdeckung:** Layer-Bounding-Box in EPSG:3857 rund um Hamburg
  (1,08–1,15 Mio / 7,05–7,12 Mio); sinnvoller Zoombereich etwa 10–19.
- **Lizenz:** Datenlizenz Deutschland Namensnennung 2.0.
  **Quellenvermerk (wörtlich aus `<Fees>`):** "Freie und Hansestadt Hamburg,
  Landesbetrieb Geoinformation und Vermessung"
- **requiresAccount:** false

### Digitale Orthophotos (DOP) -- der wertvollste Fund, nur WMS

Hamburg veröffentlicht seine Luftbilder **nicht** unter den vom Auftraggeber
geratenen Namen, sondern als zeitreihenfähigen WMS mit WMS-Time
(`Dimension name="time" ... 2001/2025/P1Y`, Standardjahr 2025):

- **Anzeigename:** "Luftbild Hamburg (DOP, unbelaubt)" /
  "Luftbild Hamburg (DOP, belaubt)"
- **Gruppe:** `Luft- und Satellitenbild`
- **Dienst unbelaubt:** `https://geodienste.hamburg.de/wms_dop_zeitreihe_unbelaubt`
  (WMS 1.3.0), Layer `dop_zeitreihe_unbelaubt`
- **Dienst belaubt:** `https://geodienste.hamburg.de/wms_dop_zeitreihe_belaubt`
  (WMS 1.3.0), Layer `dop_zeitreihe_belaubt`
- **urlTemplate (Fall 3, nur WMS):**
  `https://geodienste.hamburg.de/wms_dop_zeitreihe_unbelaubt?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=dop_zeitreihe_unbelaubt&STYLES=&CRS=EPSG:3857&BBOX={bbox-epsg-3857}&WIDTH=256&HEIGHT=256&FORMAT=image/png`
- **Beleg:** GetMap für dieselbe Kachel (HafenCity-Bereich), Status 200,
  `image/png`, 104 KB, 256×256. Bild zeigt ein scharfes, echtes Luftbild von
  Gebäuden und Hafenbecken, kein Wasserzeichen, keine Sperrgrafik.
- **WMTS geprüft und nicht gefunden:** `HH_WMS_Cache_DOP20`, `HH_WMS_DOP20`,
  `HH_WMTS_DOP20`, `wmts_dop*` -- alle 404. Es gibt für DOP in Hamburg keinen
  gekachelten WMTS, nur diesen zeitfähigen WMS.
- **Zoom/Abdeckung:** Gleiche Hamburg-Bounding-Box wie oben; 20 cm
  Bodenauflösung, sinnvoll bis Zoom ~19–20.
- **Lizenz:** Datenlizenz Deutschland Namensnennung 2.0 für die eigenen
  Daten; für einzelne Kacheln mit Satelliten-Ergänzung ("Andere geschlossene
  Lizenz") gilt laut `<Fees>` zusätzlich: "Andere geschlossene Lizenz,
  Quellenvermerk: Maxar Products. Dynamic Product © 2023 Maxar
  Technologies." Das betrifft nur einzelne Lückenschluss-Kacheln, nicht
  die Fläche.
  **Quellenvermerk (wörtlich):** "Freie und Hansestadt Hamburg, Landesbetrieb
  Geoinformation und Vermessung (LGV)"
- **requiresAccount:** false
- **Hinweis:** Die vom Geoportal-Katalog (`/api/geoportal/datasets`)
  gelisteten Hamburg-DOP-Einträge (`dkl_dop10`, `dkl_dop10b`, `dkl_dop5`,
  `gitternetze/dop5_250m_utm`, `uebersicht_kachelbezeichnungen/...`) sind
  ausnahmslos Kachelübersichten/Bildmittelpunkte, keine Bilddienste -- das
  bestätigt, was der Auftraggeber schon vermutet hatte. Der echte Dienst
  taucht dort nicht auf.

---

## Brandenburg

### DOP20c (Echtfarbe) -- eigenes Gitter, WMTS

- **Anzeigename:** "Luftbild Brandenburg/Berlin (DOP20)"
- **Gruppe:** `Luft- und Satellitenbild`
- **Capabilities:** `https://isk.geobasis-bb.de/mapproxy/dop20c_wmts/service?REQUEST=GetCapabilities&SERVICE=WMTS`
- **Layer:** `bebb_dop20c`, TileMatrixSet `grid_3857` (EPSG:3857) und
  `grid_25833`
- **Fall 2 (eigenes Gitter):** `grid_3857` hat `TopLeftCorner` = `1239392.0
  7095795.0` und 512-px-Kacheln -- **nicht** der globale Ursprung
  `-20037508/20037508`, `TileMatrix`-Kennungen `00`–`22` folgen keiner
  Standard-Zoomzahl. `{z}/{x}/{y}`-Substitution funktioniert damit nicht ohne
  eigene Umrechnung. KVP-Aufruf (kein REST-`ResourceURL` vorhanden):
  `https://isk.geobasis-bb.de/mapproxy/dop20c_wmts/service?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=bebb_dop20c&STYLE=default&TILEMATRIXSET=grid_3857&TILEMATRIX={stufe}&TILEROW={row}&TILECOL={col}&FORMAT=image/png`
- **Beleg:** Kachel für Potsdam (Glienicker-Brücke-Bereich, per errechneter
  `TILEMATRIX=14`/`TILEROW=183`/`TILECOL=175`), Status 200. `Content-Type`
  kam trotz `FORMAT=image/png` als `image/jpeg` zurück, 512×512, 108 KB.
  Bild zeigt ein scharfes echtes Luftbild (Brücke, Bahngleise, Wohnblocks),
  kein Wasserzeichen.
- **Zoom/Abdeckung:** WGS84-BBox 11,23–14,77° Ost / 51,31–53,57° Nord
  (Brandenburg + Berlin). `TileMatrix` 00–22 vorhanden, 20 cm Auflösung.
- **Lizenz:** kostenfrei, Datenlizenz Deutschland Namensnennung 2.0.
  **Quellenvermerk (wörtlich aus `<ows:AccessConstraints>`):**
  „GeoBasis-DE/LGB“, Beispiel: „© GeoBasis-DE/LGB, dl-de/by-2-0, (Daten
  geändert)“ -- und falls Berlin-Anteile genutzt werden, ergänzt um „©
  Geoportal Berlin, dl-de/by-2-0“.
- **requiresAccount:** false
- **Nebenbefund:** Die vom Auftraggeber zunächst gefundenen
  `isk.geobasis-bb.de/mapproxy/webatlasde/...` und `.../dop20c/...`-WMS-Pfade
  sind eine ältere, laut Websuche kostenpflichtige Route
  ("Entgeltpflichtig – Preis auf Anfrage"). Die hier dokumentierten
  `_wmts`-Pfade sind die seit 2020 offenen Open-Data-Dienste und ein
  eigenständiges Angebot, keine Weiterleitung der alten URLs.

### DOP20cir (Color-Infrarot) -- eigenes Gitter, WMTS, nicht bildgeprüft

- Gleicher Dienst-Unterbau, Layer `bb_dop20cir`,
  `https://isk.geobasis-bb.de/mapproxy/dop20cir_wmts/service?REQUEST=GetCapabilities&SERVICE=WMTS`.
  **Nur `grid_25833` vorhanden, kein `grid_3857`** -- für dieses Projekt ohne
  Umprojektion nicht nutzbar. Nicht mit echter Kachel geprüft, da schon am
  fehlenden Gitter scheitert.

### DOP20c -- Nachtrag: nur WMS, jetzt einsetzbar (Fall 3)

- **Anzeigename:** "Luftbild Brandenburg/Berlin (DOP20)"
- **Gruppe:** `Luft- und Satellitenbild`
- **Dienst:** `https://isk.geobasis-bb.de/mapproxy/dop20c/service/wms` (WMS
  1.3.0), Layer `bebb_dop20c`
- **urlTemplate (Fall 3, nur WMS):**
  `https://isk.geobasis-bb.de/mapproxy/dop20c/service/wms?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=bebb_dop20c&STYLES=&CRS=EPSG:3857&BBOX={bbox-epsg-3857}&WIDTH=256&HEIGHT=256&FORMAT=image/png`
- **Beleg:** GetMap für dieselbe Potsdam-Kachel wie beim WMTS-Test, Status
  200, `image/png`, 156 KB, 256×256. Scharfes echtes Luftbild, kein
  Wasserzeichen.
- **Klärung zum Nebenbefund aus der ersten Runde:** Der als vermutlich
  entgeltpflichtig eingestufte Pfad
  `isk.geobasis-bb.de/mapproxy/webatlasde/service/wms` (ohne Jahressuffix,
  siehe unten bei WebAtlasDE) ist ein **anderer, toter Legacy-Pfad** und hat
  nichts mit diesem DOP-Dienst zu tun. Dieser hier,
  `.../mapproxy/dop20c/service/wms`, nennt in seinen eigenen Capabilities
  unter `<ows:Fees>` ausdrücklich **"kostenfrei, unter Beachtung der
  Lizenzbedingungen"** -- also derselbe freie Datenlizenz-Deutschland-Status
  wie der WMTS-Zwilling, keine Rechnung zu befürchten.
- **Lizenz:** kostenfrei, Datenlizenz Deutschland Namensnennung 2.0.
  **Quellenvermerk:** wie oben beim WMTS, wörtlich „GeoBasis-DE/LGB“, Beispiel
  „© GeoBasis-DE/LGB, dl-de/by-2-0, (Daten geändert)“.
- **requiresAccount:** false
- **Für den Katalog reicht dieser WMS-Eintrag.** Der Fall-2-WMTS oben bleibt
  nur als Beleg stehen.

### WebAtlasDE 2024 -- nur WMS

- **Anzeigename:** "WebAtlasDE Brandenburg/Berlin (Halbton)" /
  "... (Grau)"
- **Gruppe:** `Bundesländer`
- **Dienst:** `https://isk.geobasis-bb.de/mapproxy/webatlasde_2024/service/wms`
  (WMS), Layer `WebAtlasDE_BEBB_halbton` bzw. `WebAtlasDE_BEBB_grau`
- **urlTemplate (Fall 3, nur WMS):**
  `https://isk.geobasis-bb.de/mapproxy/webatlasde_2024/service/wms?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=WebAtlasDE_BEBB_halbton&STYLES=&CRS=EPSG:3857&BBOX={bbox-epsg-3857}&WIDTH=256&HEIGHT=256&FORMAT=image/png`
- **Beleg:** GetMap für Potsdam Hauptbahnhof, Status 200, `image/png`, 70 KB.
  Saubere Straßenkarte, keine Wasserzeichen.
- **Lizenz:** kostenfrei, Datenlizenz Deutschland Namensnennung 2.0, gleicher
  Quellenvermerk wie oben ("GeoBasis-DE/LGB", ggf. + Geoportal Berlin).
- **requiresAccount:** false
- **Verworfen:** `webatlasde/service/wms` (ohne Jahressuffix) -- 404, toter
  Legacy-Pfad. `webatlasde_flex/service/wms` -- 404. Nur die
  Jahresvarianten (`_2016`, `_2019`, `_2021`, `_2024`) sind erreichbar;
  geprüft wurden `_2021` und `_2024`, beide 200.

---

## Nordrhein-Westfalen

### DOP -- eigenes Gitter (Offset), WMTS, sofort fast einsetzbar

- **Anzeigename:** "Luftbild Nordrhein-Westfalen (DOP)"
- **Gruppe:** `Luft- und Satellitenbild`
- **Capabilities:** `https://www.wmts.nrw.de/geobasis/wmts_nw_dop?SERVICE=WMTS&REQUEST=GetCapabilities`
  (antwortet mit 303 auf
  `https://www.wmts.nrw.de/geobasis/wmts_nw_dop/1.0.0/WMTSCapabilities.xml`)
- **Layer:** `nw_dop`, TileMatrixSet `EPSG_3857_16`
- **Fall 2 (eigenes Gitter, aber nur eine feste Verschiebung):** Ursprung ist
  der globale Standardursprung (`-20037508.34/20037508.34`), 256-px-Kacheln,
  **aber** die `TileMatrix`-Kennung „00" hat schon `MatrixWidth=32` -- das
  entspricht Zoomstufe 5. Kennung `N` = Zoomstufe `N+5`, zweistellig mit
  führender Null (`00`…`16` = Zoom 5…21). Ein `{z}/{x}/{y}`-Template
  funktioniert nur, wenn die aufrufende Seite `z-5` als zweistellige Zahl
  einsetzt -- keine reine Textersetzung.
  **ResourceURL aus den Capabilities (REST, mit Platzhaltern):**
  `https://www.wmts.nrw.de/geobasis/wmts_nw_dop/tiles/nw_dop/{TileMatrixSet}/{TileMatrix}/{TileCol}/{TileRow}`
  (Format-Suffix nicht nötig, Reihenfolge ist Spalte vor Zeile, also `x`
  vor `y` -- anders als bei WMTS sonst oft `{y}` vor `{x}` steht).
- **Beleg:** Kachel für Düsseldorf-Altstadt (Zoom 17 → Kennung `12`, x=68002,
  y=43747), URL
  `.../tiles/nw_dop/EPSG_3857_16/12/68002/43747`, Status 200, `image/jpeg`,
  256×256, 31 KB. Scharfes echtes Luftbild von Häuserblocks, kein
  Wasserzeichen.
- **Zoom/Abdeckung:** `TileMatrix` 00–16 = Zoom 5–21, ganz NRW.
- **Lizenz:** gebührenfrei nach Open-Data-Prinzipien (VermKatG NRW).
  Nutzungsbedingungen laut `<ows:Fees>` unter
  `https://www.bezreg-koeln.nrw.de/system/files/media/document/file/lizenzbedingungen_geobasis_nrw.pdf`
  -- der genaue Quellenvermerk-Wortlaut steht in diesem PDF, ich habe ihn
  nicht abgetippt, um ihn nicht falsch zu zitieren.
- **requiresAccount:** false
- **Verworfen:** die vom Auftraggeber geratene REST-URL
  `.../wmts_nw_dop/tiles/wmts_nw_dop/EPSG_3857_16/{z}/{x}/{y}.png` hatte
  gleich zwei Fehler: Layername muss `nw_dop` heißen (ohne `wmts_`-Präfix),
  und `{z}` braucht die -5-Verschiebung, nicht die reine Zoomzahl. Daher der
  400er.

### DOP -- Nachtrag: nur WMS, jetzt einsetzbar (Fall 3)

- **Anzeigename:** "Luftbild Nordrhein-Westfalen (DOP)"
- **Gruppe:** `Luft- und Satellitenbild`
- **Dienst:** `https://www.wms.nrw.de/geobasis/wms_nw_dop` (WMS 1.3.0),
  Layer `nw_dop_rgb` (daneben `nw_dop_cir`, `nw_dop_nir`)
- **urlTemplate (Fall 3, nur WMS):**
  `https://www.wms.nrw.de/geobasis/wms_nw_dop?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=nw_dop_rgb&STYLES=&CRS=EPSG:3857&BBOX={bbox-epsg-3857}&WIDTH=256&HEIGHT=256&FORMAT=image/png`
- **Beleg:** GetMap für dieselbe Düsseldorf-Altstadt-Kachel wie beim
  WMTS-Test, Status 200, `image/png`, 142 KB, 256×256. Scharfes echtes
  Luftbild von Häuserblocks, kein Wasserzeichen.
- **Zoom/Abdeckung:** ganz NRW, 10 cm Auflösung.
- **Lizenz:** Datenlizenz Deutschland **Zero** 2.0
  (`https://www.govdata.de/dl-de/zero-2-0`, laut `<Fees>`).
  **AccessConstraints (wörtlich):** "NONE". Diese WMS-Capabilities nennen die
  Lizenz klarer als der WMTS-Auszug oben (Zero statt nur "gebührenfrei") --
  also **keine Namensnennungspflicht.**
- **requiresAccount:** false
- **Für den Katalog reicht dieser WMS-Eintrag.** Der Fall-2-WMTS oben bleibt
  nur als Beleg stehen, falls das Team die Entscheidung zu Fall 2 noch
  einmal überdenkt.

---

## Bayern

### Amtliche Karten und DOP -- sofort einsetzbar, sauberstes Ergebnis der Recherche

- **Capabilities:** `https://geoservices.bayern.de/od/wmts/geobasis/v1/1.0.0/WMTSCapabilities.xml`
- **Kachel-Hosts:** `wmtsod1.bayernwolke.de` bis `wmtsod5.bayernwolke.de`
  (mehrere Server für Lastverteilung, austauschbar)
- **TileMatrixSet `smerc`:** offiziell benannt "Spherical Mercator
  Projection", `WellKnownScaleSet =
  urn:ogc:def:wkss:OGC:1.0:GoogleMapsCompatible`, Ursprung
  `-20037508.34278924/20037508.34278924`, 256-px-Kacheln,
  **Kennung == Zoomstufe direkt (0–19), keine Verschiebung.** Das ist exakt
  die Form, die der Vertrag mit `basemap.de` als Beispiel zeigt.
- **Layer und `urlTemplate` (Fall 1, sofort einsetzbar):**
  - "Amtliche Karte Bayern (farbig)": `by_webkarte` --
    `https://wmtsod1.bayernwolke.de/wmts/by_webkarte/smerc/{z}/{x}/{y}`
  - "Amtliche Karte Bayern (grau)": `by_webkarte_grau` --
    `https://wmtsod1.bayernwolke.de/wmts/by_webkarte_grau/smerc/{z}/{x}/{y}`
  - "Luftbild Bayern (DOP)": `by_dop` --
    `https://wmtsod1.bayernwolke.de/wmts/by_dop/smerc/{z}/{x}/{y}`
  - "Luftbild Bayern (DOP, CIR)": `by_dop_cir` --
    `https://wmtsod1.bayernwolke.de/wmts/by_dop_cir/smerc/{z}/{x}/{y}`
  - Format laut Capabilities `image/jpeg` für die DOP-Layer,
    `image/png` für die Kartenlayer -- keine Endung nötig, der Server liefert
    den richtigen `Content-Type`.
- **Gruppe:** `by_webkarte`/`by_webkarte_grau` → `Bundesländer`;
  `by_dop`/`by_dop_cir` → `Luft- und Satellitenbild`
- **Beleg:** Kachel für München-Marienplatz (Zoom 17, x=69750, y=45487):
  - `by_dop`: Status 200, `image/jpeg`, 256×256, 15,8 KB -- scharfes echtes
    Luftbild vom Marienplatz, kein Wasserzeichen.
  - `by_webkarte`: Status 200, `image/png`, 256×256, 63 KB -- saubere
    Stadtkarte mit Beschriftung (Rathausgalerie, Marienplatz), kein
    Wasserzeichen.
- **Zoom/Abdeckung:** Zoom 0–19, ganz Bayern.
- **Lizenz:** kostenfrei laut `<ows:Fees>`.
  **Quellenvermerk-Pflicht laut `<ows:AccessConstraints>`:** "Bei der
  externen Nutzung des Dienstes ist ein Quellenvermerk anzugeben." Der genaue
  Wortlaut steht unter
  `https://geodaten.bayern.de/odd/m/3/pdf/WMTS_Nutzungshinweise.pdf` -- ich
  zitiere ihn nicht aus dem Gedächtnis, weil der Vertrag genau das verbietet.
  Websuche nennt zusätzlich CC BY 4.0 für die zugrundeliegenden Datensätze;
  das PDF ist trotzdem die verbindliche Quelle für den genauen Text.
- **requiresAccount:** false
- **Verworfen:** die vom Auftraggeber geratene URL
  `intergeo1.bayernwolke.de/betty/g_webkarte/{z}/{x}/{y}` traf einen nicht
  existierenden Host (daher Timeout/000) und einen falschen Layernamen
  (`g_webkarte` statt `by_webkarte`).

---

## Berlin

### TrueDOP 2024 -- nur WMS (WMTS existiert, aber ohne Web-Mercator-Gitter)

- **Anzeigename:** "Luftbild Berlin (TrueDOP 2024)"
- **Gruppe:** `Luft- und Satellitenbild`
- **WMS-Dienst:** `https://gdi.berlin.de/services/wms/truedop_2024` (WMS
  1.3.0), Layer `truedop_2024`
- **urlTemplate (Fall 3, nur WMS):**
  `https://gdi.berlin.de/services/wms/truedop_2024?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=truedop_2024&STYLES=&CRS=EPSG:3857&BBOX={bbox-epsg-3857}&WIDTH=256&HEIGHT=256&FORMAT=image/png`
- **Beleg:** GetMap für den Bereich am Brandenburger Tor, Status 200,
  `image/png`, 155 KB, 256×256, echtes scharfes Luftbild (Straße, Rasen,
  Gebäude), kein Wasserzeichen.
- **WMTS existiert auch** (`https://gdi.berlin.de/services/wmts/truedop_2024?REQUEST=GetCapabilities&SERVICE=wmts`,
  200, ebenso `k5_farbe` für die Basiskarte), **aber beide bieten nur das
  TileMatrixSet `GDIBE:25833` (ETRS89/UTM33) an, kein Web-Mercator-Gitter.**
  Für dieses Projekt daher nicht direkt nutzbar, ohne dass jemand die
  Kachel-Koordinaten umprojiziert -- deshalb hier der WMS-Weg dokumentiert.
- **Zoom/Abdeckung:** Stadtgebiet Berlin, 20 cm Auflösung, sinnvoll bis Zoom
  ~19.
- **Lizenz:** Datenlizenz Deutschland **Zero** 2.0
  (`https://www.govdata.de/dl-de/zero-2-0`) -- anders als die meisten
  anderen Länder hier **keine Namensnennung vorgeschrieben.**
  **AccessConstraints (wörtlich):** "Es gelten keine Zugriffsbeschränkungen."
- **requiresAccount:** false
- **Hinweis:** `gdi.berlin.de/services/wmts/dop_2025_fruehjahr` (von der
  Websuche als älterer Treffer vorgeschlagen) ist 404 -- vermutlich ein
  inzwischen umbenannter/ersetzter Datensatz. `truedop_2024` ist der
  aktuell erreichbare.

---

## Niedersachsen

### DOP20 -- nur WMS

- **Anzeigename:** "Luftbild Niedersachsen (DOP20)"
- **Gruppe:** `Luft- und Satellitenbild`
- **Dienst:** `https://opendata.lgln.niedersachsen.de/doorman/noauth/dop_wms`
  (WMS 1.3.0), Layer `ni_dop20`
- **urlTemplate (Fall 3, nur WMS):**
  `https://opendata.lgln.niedersachsen.de/doorman/noauth/dop_wms?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=ni_dop20&STYLES=&CRS=EPSG:3857&BBOX={bbox-epsg-3857}&WIDTH=256&HEIGHT=256&FORMAT=image/png`
- **Beleg:** GetMap für Hannover (Wasserturm/Innenstadt), Status 200,
  `image/png`, 165 KB, echtes scharfes Luftbild, kein Wasserzeichen.
- **Zoom/Abdeckung:** ganz Niedersachsen, 20 cm Auflösung.
- **Lizenz:** Creative Commons Namensnennung 4.0 International (CC BY 4.0).
  **AccessConstraints (Auszug, wörtlich):** "... mit den dort geforderten
  Angaben zum Quellenvermerk. Als Rechteinhaber und Bereitsteller ist
  \"LGLN\", sowie das Jahr des Datenbezugs in Klamm[ern anzugeben]" (Text
  war an dieser Stelle in der Capabilities-Antwort abgeschnitten -- vor
  Einsatz die volle `AccessConstraints`-Zeile aus
  `ni_dop_wms.xml` noch einmal vollständig nachlesen).
- **requiresAccount:** false

---

## Schleswig-Holstein

### DOP20 -- nur WMS (WMTS existiert, aber ohne Web-Mercator-Gitter)

- **Anzeigename:** "Luftbild Schleswig-Holstein (DOP20)"
- **Gruppe:** `Luft- und Satellitenbild`
- **WMS-Dienst:** `https://dienste.gdi-sh.de/WMS_SH_DOP20col_OpenGBD` (WMS
  1.3.0), Layer `sh_dop20_rgb`
- **urlTemplate (Fall 3, nur WMS):**
  `https://dienste.gdi-sh.de/WMS_SH_DOP20col_OpenGBD?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=sh_dop20_rgb&STYLES=&CRS=EPSG:3857&BBOX={bbox-epsg-3857}&WIDTH=256&HEIGHT=256&FORMAT=image/png`
- **Beleg:** GetMap für Kiel, Status 200, `image/png`, 142 KB, echtes
  scharfes Luftbild (Parkplätze, Wohnblocks), kein Wasserzeichen.
- **WMTS existiert auch**
  (`WMTS_SH_DOP20col_OpenGBD/service?request=GetCapabilities&service=wmts&version=1.0.0`,
  200), **aber nur mit TileMatrixSet `DE_EPSG_25832_ADV`, kein
  Web-Mercator-Gitter, kein REST-`ResourceURL`.** Nicht als `{z}/{x}/{y}`
  nutzbar, deshalb wie bei Berlin der WMS-Weg dokumentiert.
- **Lizenz:** Creative Commons (CC BY 4.0).
  **Quellenvermerk (wörtlich):** "© GeoBasis-DE/LVermGeo SH/CC BY 4.0"
- **requiresAccount:** false

---

## Sachsen

### DOP-RGB -- eigenes Gitter, WMTS

- **Anzeigename:** "Luftbild Sachsen (DOP)"
- **Gruppe:** `Luft- und Satellitenbild`
- **Capabilities:** `https://geodienste.sachsen.de/wmts_geosn_dop-rgb/guest?REQUEST=GetCapabilities&SERVICE=WMTS`
- **Layer:** `sn_dop_020`, TileMatrixSet `grid_3857` (auch `grid_25833`,
  `grid_31468`, `grid_4326`)
- **Fall 2 (eigenes Gitter):** `grid_3857` hat `TopLeftCorner` = `1302438.04
  6746062.32` -- eigener, nicht-globaler Ursprung wie bei Brandenburg, keine
  einfache `{z}/{x}/{y}`-Ersetzung möglich. KVP-Aufruf:
  `https://geodienste.sachsen.de/wmts_geosn_dop-rgb/guest?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=sn_dop_020&STYLE=default&TILEMATRIXSET=grid_3857&TILEMATRIX={stufe}&TILEROW={row}&TILECOL={col}&FORMAT=image/png`
- **Beleg:** Kachel für Dresden-Altstadt (errechnet: `TILEMATRIX=12`,
  `TILEROW=378`, `TILECOL=741`), Status 200, `image/png` (8-Bit indiziert),
  256×256, 60 KB. Bild zeigt ein echtes Luftbild von Altstadt-Dächern und
  einem Platz, kein Wasserzeichen.
- **Lizenz:** kostenfrei laut `<ows:Fees>`, Nutzungsbedingungen unter
  `http://geoportal.sachsen.de/cps/geosn.html` verlinkt -- exakter
  Quellenvermerk-Wortlaut dort nachzulesen, nicht in den Capabilities
  ausgeschrieben.
- **requiresAccount:** false

### DOP-RGB -- Nachtrag: nur WMS, jetzt einsetzbar (Fall 3)

- **Dienst:** `https://geodienste.sachsen.de/wms_geosn_dop-rgb/guest` (WMS
  1.3.0), Layer `sn_dop_020` -- derselbe Layername wie beim WMTS, gleicher
  Datenbestand.
- **urlTemplate (Fall 3, nur WMS):**
  `https://geodienste.sachsen.de/wms_geosn_dop-rgb/guest?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=sn_dop_020&STYLES=&CRS=EPSG:3857&BBOX={bbox-epsg-3857}&WIDTH=256&HEIGHT=256&FORMAT=image/png`
- **Beleg:** GetMap für dieselbe Dresden-Altstadt-Kachel wie beim
  WMTS-Test, Status 200, `image/png`, 126 KB, 256×256, echtes Luftbild
  (Frauenkirche/Neumarkt-Bereich), kein Wasserzeichen.
- **Lizenz:** kostenfrei laut `<Fees>`, Nutzungsbedingungen unter
  `https://geoportal.sachsen.de/nutzungsbedingungen.html` -- exakter
  Quellenvermerk-Wortlaut dort nachzulesen.
- **requiresAccount:** false
- **Für den Katalog reicht dieser WMS-Eintrag.** Der Fall-2-WMTS oben bleibt
  nur als Beleg stehen.

---

## Baden-Württemberg

### Basiskarte -- gefunden, aber mit Vorbehalt

- **Capabilities:** `https://owsproxy.lgl-bw.de/owsproxy/ows/WMTS_LGL-BW_Basiskarte?SERVICE=WMTS&VERSION=1.0.0&REQUEST=GetCapabilities&user=ZentrKomp&password=viewerprod`
- **Layer:** `Basiskarte`, TileMatrixSet `GoogleMapsCompatible` (Fall 1,
  Standardgitter, Kennung `GoogleMapsCompatible:N` mit `N` = Zoomstufe).
- **Beleg:** KVP-`GetTile` für Stuttgart (Zoom 16, x=34439, y=22568) mit
  `TILEMATRIX=GoogleMapsCompatible:16`, Status 200, `image/png`, 256×256,
  55 KB, echte Straßenkarte, kein Wasserzeichen.
- **Vorbehalt, den ich nicht selbst auflösen wollte:** Die Capabilities-URL
  **und** jede `GetTile`-Anfrage funktionierten nur mit den Parametern
  `user=ZentrKomp&password=viewerprod`, die ich über eine Websuche gefunden
  habe (öffentlich indexierte Suchtreffer-URL), nicht aus einer offiziellen
  "so nutzen Sie den offenen Dienst"-Seite des LGL. Ich habe nicht geprüft,
  ob das ein offiziell für Drittanwendungen vorgesehenes Sammel-Login ist
  oder ein Zugang, der eigentlich für einen bestimmten Viewer gedacht war.
  Fest einprogrammierte Zugangsdaten in einem öffentlichen Katalogeintrag
  sind außerdem grundsätzlich heikel: das Land kann sie jederzeit ändern
  oder sperren, ohne dass hGIS etwas davon merkt außer einem plötzlich toten
  Dienst. Ich empfehle, das **nicht** ungeprüft zu übernehmen, sondern beim
  LGL nachzufragen oder die offizielle Open-Data-Seite
  (`https://www.lgl-bw.de/Produkte/Open-Data/index.html`) nach einem
  Dienst ohne eingebettete Zugangsdaten zu durchsuchen. Ich habe das aus
  Zeitgründen nicht mehr getan.
- **requiresAccount:** vorläufig `true`, bis das geklärt ist.

---

## Thüringen

### DOP -- nur WMS

- **Anzeigename:** "Luftbild Thüringen (DOP)"
- **Gruppe:** `Luft- und Satellitenbild`
- **Dienst:** `https://www.geoproxy.geoportal-th.de/geoproxy/services/DOP`
  (WMS), Layer `th_dop` (True-Color-Mosaik)
- **urlTemplate (Fall 3, nur WMS):**
  `https://www.geoproxy.geoportal-th.de/geoproxy/services/DOP?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=th_dop&STYLES=&CRS=EPSG:3857&BBOX={bbox-epsg-3857}&WIDTH=256&HEIGHT=256&FORMAT=image/png`
- **Beleg:** GetMap für Erfurt (Flussufer nahe Altstadt), Status 200,
  `image/png`, 161 KB, echtes Luftbild, kein Wasserzeichen.
- **Achtung, Klasse-Fehler mit Status 200:** `LAYERS=th_dop20rgb` (naheliegend
  geratener Name für die 20-cm-Echtfarbvariante) existiert **nicht** als
  Layer und liefert bei falschem Namen sofort eine XML-Fehlermeldung, kein
  Bild. Für einen anderen existierenden, aber zugriffsbeschränkten Layer
  (`th_dop20cir` unter falschem Testnamen) kam Status 200 mit
  `Content-Type: text/xml` und der Meldung "Missing WMS:GetMap right for
  object(s) ..." zurück -- **sieht auf den ersten Blick nach Erfolg aus,
  ist aber keiner.** Nur `th_dop` lieferte ein echtes Bild.
- **Lizenz:** Datenlizenz Deutschland Namensnennung 2.0.
  **AccessConstraints (wörtlich):** "NONE. Es gelten keine Beschränkungen."
- **requiresAccount:** false

---

## Bremen

### DOP20 2023 -- nur WMS

- **Anzeigename:** "Luftbild Bremen (DOP20 2023)"
- **Gruppe:** `Luft- und Satellitenbild`
- **Dienst:** `https://geodienste.bremen.de/wms_dop20_2023` (WMS 1.3.0),
  Layer `DOP20_2023_HB` (Bremen) bzw. `DOP20_2023_BHV` (Bremerhaven)
- **urlTemplate (Fall 3, nur WMS):**
  `https://geodienste.bremen.de/wms_dop20_2023?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=DOP20_2023_HB&STYLES=&CRS=EPSG:3857&BBOX={bbox-epsg-3857}&WIDTH=256&HEIGHT=256&FORMAT=image/png`
- **Beleg:** GetMap für Bremen-Innenstadt, Status 200, `image/png`, 178 KB,
  echtes Luftbild, kein Wasserzeichen.
- **Lizenz:** Creative Commons Namensnennung (CC-BY).
  **Quellenvermerk (wörtlich aus `<Fees>`):** "Landesamt GeoInformation
  Bremen"
- **requiresAccount:** false

---

## Sachsen-Anhalt

### DOP20 (OpenData-Dienst) -- nur WMS, Vorsicht bei der Dienstwahl

- **Anzeigename:** "Luftbild Sachsen-Anhalt (DOP20)"
- **Gruppe:** `Luft- und Satellitenbild`
- **Dienst:** `https://www.geodatenportal.sachsen-anhalt.de/wss/service/ST_LVermGeo_DOP_WMS_OpenData/guest`
  (WMS 1.3.0), Layer `lsa_lvermgeo_dop20_2`
- **urlTemplate (Fall 3, nur WMS):**
  `https://www.geodatenportal.sachsen-anhalt.de/wss/service/ST_LVermGeo_DOP_WMS_OpenData/guest?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=lsa_lvermgeo_dop20_2&STYLES=&CRS=EPSG:3857&BBOX={bbox-epsg-3857}&WIDTH=256&HEIGHT=256&FORMAT=image/png`
- **Beleg:** GetMap für Magdeburg-Innenstadt, Status 200, `image/png`, 94 KB,
  echtes Luftbild (Kreuzung, Häuserzeilen), kein Wasserzeichen.
- **Wichtig -- zwei Dienste nicht verwechseln:** Es gibt einen zweiten,
  ähnlich benannten Dienst `ST_LVermGeo_GDI_DOP20` mit Layer
  `lsa_lvermgeo_dop20` (ohne `_2`), dessen `<Fees>`-Feld ausdrücklich
  "Kostenverordnung für das amtliche Vermessungswesen Sachsen-Anhalt" nennt
  -- **das ist der kostenpflichtige Dienst.** Nur der oben dokumentierte
  `..._OpenData`-Dienst mit `lsa_lvermgeo_dop20_2` nannte keine Gebühr.
- **Lizenz:** Im Capabilities-Dokument dieses OpenData-Diensts stand kein
  `<Fees>`/`<AccessConstraints>`-Text -- vor Übernahme beim LVermGeo
  Sachsen-Anhalt den genauen Lizenztext erfragen oder das Metadaten-Portal
  konsultieren, statt hier eine Formulierung zu raten.
- **requiresAccount:** false

---

## Hessen

### DOP -- nur WMS (WMTS existiert, aber ohne Web-Mercator-Gitter)

- **Anzeigename:** "Luftbild Hessen (DOP)"
- **Gruppe:** `Luft- und Satellitenbild`
- **WMS-Dienst:**
  `https://www.gds-srv.hessen.de/cgi-bin/lika-services/de-viewer/access/ogc-free-images.ows`
  (WMS 1.1.1), Layer `he_dop_rgb`
- **urlTemplate (Fall 3, nur WMS, beachte `SRS` statt `CRS` bei WMS 1.1.1):**
  `https://www.gds-srv.hessen.de/cgi-bin/lika-services/de-viewer/access/ogc-free-images.ows?SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap&LAYERS=he_dop_rgb&STYLES=&SRS=EPSG:3857&BBOX={bbox-epsg-3857}&WIDTH=256&HEIGHT=256&FORMAT=image/png`
- **Beleg:** GetMap für Frankfurt-Innenstadt, Status 200, `image/png`,
  138 KB, echtes Luftbild (Platz mit Brunnen, Gebäude), kein Wasserzeichen.
- **WMTS existiert auch**
  (`https://www.gds-srv.hessen.de/wmts-dop/wmts/1.0.0/WMTSCapabilities.xml`,
  200, Layer `he_dop_25832`/`he_dop_4258`), **aber nur mit den Gittern
  `advgrid_25832`/`advgrid_4258`, kein Web-Mercator.** Deshalb WMS-Weg
  dokumentiert.
- **Lizenz:** kostenfrei nach § 24 Hessisches Vermessungs- und
  Geoinformationsgesetz (HVGG). **AccessConstraints (Auszug, wörtlich):**
  "Jede Nutzung der Geobasisdaten und zugehörigen Metadaten ist ohne
  Einschränkung oder Bedingung erlaubt." -- keine Namensnennungspflicht
  erkennbar, aber vor Übernahme den vollständigen Text aus `he_dop_wms.xml`
  noch einmal ganz lesen, er war in meinem Auszug abgeschnitten.
- **requiresAccount:** false

---

## Mecklenburg-Vorpommern

### DOP -- WMTS gefunden, aber nicht verifiziert (Fall 2)

- **Capabilities (funktioniert):**
  `https://www.geodaten-mv.de/dienste/dop_wmts/wmts/1.0.0/WMTSCapabilities.xml`,
  Status 200. Layer `mv_dop`, TileMatrixSet `GoogleMapsCompatible` **ist
  vorhanden** und sieht nach Fall 1 aus (Standardursprung, aber Kennung `00`
  hat wieder `MatrixWidth=32`, also derselbe -5-Offset wie NRW: Kennung `N`
  = Zoom `N+5`).
- **Aber:** Jeder `GetTile`-Versuch scheiterte. Getestet wurden KVP gegen
  `.../wmts/1.0.0` und `.../wmts` sowie ein REST-Pfad
  `.../wmts/1.0.0/mv_dop/default/GoogleMapsCompatible/{stufe}/{row}/{col}` --
  alle drei kamen mit `internal error: invalid request` (HTTP 500) oder
  404 zurück, nie mit einem Bild. Seit Fall 2 ohnehin draußen bleibt, war
  das kein Problem mehr -- gelöst wurde es über den WMS-Zwilling unten.

### DOP -- Nachtrag: nur WMS, jetzt einsetzbar (Fall 3)

- **Anzeigename:** "Luftbild Mecklenburg-Vorpommern (DOP)"
- **Gruppe:** `Luft- und Satellitenbild`
- **Dienst:** `https://www.geodaten-mv.de/dienste/adv_dop` (WMS 1.3.0),
  Layer `mv_dop`. **Nicht** mit `adv_dop20` verwechseln (siehe unten).
- **urlTemplate (Fall 3, nur WMS):**
  `https://www.geodaten-mv.de/dienste/adv_dop?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=mv_dop&STYLES=&CRS=EPSG:3857&BBOX={bbox-epsg-3857}&WIDTH=256&HEIGHT=256&FORMAT=image/png`
- **Beleg:** GetMap für Schwerin (See-/Uferbereich), Status 200,
  `image/png`, 115 KB, 256×256. Echtes scharfes Luftbild eines Sees mit
  Steg und Parkplätzen. Die Kachel trägt zusätzlich ein eingebranntes
  "© GeoBasis-DE/M-V"-Logo unten links -- kein Sperrvermerk, sondern die
  vorgeschriebene Quellenangabe direkt im Bild.
- **Achtung, zwei ähnlich benannte Dienste nicht verwechseln:** `adv_dop`
  (Layer `mv_dop`) nennt unter `<Fees>` wörtlich "Es gelten keine
  Bedingungen" -- das ist der freie Dienst. Der ähnlich benannte
  `adv_dop20` (ebenfalls Layer `mv_dop`, andere Auflösungsstufe) nennt
  dagegen wörtlich "Nutzungsbedingungen: es gibt keine Eignungs-, nur
  Zugriffseinschränkungen. Für die Nutzung können Kosten anfallen." und
  verlinkt eine Entgelt-PDF (`Entgelte_Geobasisdaten_LAiV.pdf`) --
  **`adv_dop20` daher nicht verwenden.**
- **Lizenz:** frei laut `<Fees>` ("Es gelten keine Bedingungen").
  **Quellenvermerk (wörtlich aus `<AccessConstraints>`):** "Der
  Lizenznehmer ist verpflichtet, bei jeder öffentlichen Wiedergabe,
  Verbreitung oder Präsentation der Geodaten sowie bei jeder Veröffentlichung
  oder externen Nutzung einer Bearbeitung oder Umgestaltung einen deutlich
  sichtbaren Quellenvermerk anzubringen, der wie folgt auszugestalten ist:
  © GeoBasis-DE/M-V <Jahr der letzten Datenlieferung>"
- **requiresAccount:** false

---

## Geprüft und verworfen

| Dienst | Grund |
|---|---|
| Rheinland-Pfalz, DOP20 (`geo4.service24.rlp.de/wms/rp_dop20.fcgi`) | Laut Nutzungsbedingungen nur die Anzeige im offiziellen Geoportal/den Verfahren des Landes ist kostenfrei; "jede andere Nutzung, wie z. B. Einbindung in weitere Anwendungen oder Downloads, ist kostenpflichtig und vertraglich zu vereinbaren." Für einen Katalogeintrag in hGIS damit nicht geeignet. |
| Rheinland-Pfalz, DOP40 (`geo4.service24.rlp.de/wms/rp_dop40.fcgi`) | Laut Websuche als "frei" beschrieben, aber die geratene URL lieferte HTTP 403 mit Verweis auf `https://lvermgeo.rlp.de/geodaten-geoshop/open-data`. Aus Zeitgründen nicht weiterverfolgt -- echte URL muss von dort abgelesen werden, nicht geraten. |
| Saarland, DOP (`geoportal.saarland.de/freewms/dop`) | Laut Geoportal-Hinweis ist nur die Anzeige in den Online-Verfahren des LVGL/Geoportal Saarland kostenfrei; jede andere Nutzung ist kostenpflichtig und vertraglich zu vereinbaren -- gleiche Einschränkung wie RLP. Nicht weiter geprüft. |
| Hamburg, alle vom Auftraggeber ursprünglich geratenen URLs (`HH_WMTS_Geobasiskarten`, `HH_WMS_Cache_DOP20`, `HH_WMS_DOP20`, `HH_WMTS_DOP20`, `HH_WMTS_DOP`, `HH_WMS_Luftbilder`, `HH_WMS_Orthophotos`, `HH_WMS_Orthofotos`) | Alle erneut mit 404 bestätigt. Es gibt für Hamburg keinen WMTS, nur die oben dokumentierten WMS-Dienste. |
| Brandenburg, `isk.geobasis-bb.de/mapproxy/webatlasde/service/wms` (ohne Jahressuffix) und `.../webatlasde/service/wmts` | 404, toter Legacy-Pfad. `.../bebb-webatlasde/service`, `.../webatlasde_wmts/service`, `.../webatlasde/service` als WMTS -- alle 404. |
| Berlin, `gdi.berlin.de/services/wmts/dop_2025_fruehjahr` | 404, vermutlich umbenannt/ersetzt durch `truedop_2024`. |
| NRW, `wms_nw_stadtplangrau` (vom Auftraggeber geraten) | Nicht erneut geprüft, da NRW-DOP schon einen funktionierenden Treffer lieferte und die Zeit für die niedriger priorisierten Länder knapp wurde; sollte bei Bedarf jemand mit demselben Muster wie `nw_dop` (Layername ohne `wmts_`-Präfix, `EPSG_3857_16`-Gitter mit dem -5-Offset) prüfen. |
| `maps.wikimedia.org/osm-intl` | Nicht erneut geprüft -- kein Landesdienst, war ohnehin außerhalb des Auftrags (nur zur Vollständigkeit gegenüber der Liste des Auftraggebers erwähnt). |

## Auffällige "Status 200, aber unbrauchbar"-Fälle

Wie gewünscht einzeln benannt, weil das genau die gefährliche Fehlerklasse
ist:

1. **Thüringen, Layer `th_dop20cir` unter geratenem Namen `th_dop20rgb`:**
   Erste Anfrage kam mit HTTP 200 und `Content-Type: text/xml` zurück --
   sieht auf den ersten Blick nach Erfolg aus, war aber eine
   `ServiceExceptionReport` mit "Missing WMS:GetMap right for object(s)
   th_dop20rgb." Der Layername existierte schlicht nicht; erst `th_dop`
   lieferte ein echtes Bild.
2. **Brandenburg, DOP20c-WMTS:** `FORMAT=image/png` angefragt, Server
   antwortete mit HTTP 200 und einem echten Bild, aber `Content-Type:
   image/jpeg` -- kein Fehler, aber eine Abweichung zwischen angefragtem und
   geliefertem Format, die beim `paint`/Format-Handling im Frontend
   berücksichtigt werden sollte.
3. **Sachsen, DOP-WMTS:** Kachel kam mit HTTP 200 und echtem, unverfälschtem
   Bildinhalt, aber als 8-Bit-Palettenbild (`PNG ... 8-bit colormap`) statt
   der sonst üblichen 24-Bit-RGB-Kachel -- kein Darstellungsfehler, aber
   ungewöhnlich genug, um es hier festzuhalten, falls beim Rendering im
   Frontend Farbtiefen-Annahmen gemacht werden.

Kein Fall wie die vom Auftraggeber erwähnte CARTO-"API KEY
REQUIRED"-Wasserzeichenkachel ist mir begegnet -- alle mit echtem Bildinhalt
gelieferten Kacheln oben zeigten tatsächlich das beworbene Kartenbild.
