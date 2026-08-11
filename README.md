![hGIS-Logo](frontend/public/logo.png)

# hGIS

hGIS ist eine browserbasierte Anwendung zum Verwalten, Darstellen und Bearbeiten von
Geodaten. Sie verbindet eine interaktive Karte mit Projektverwaltung, Layerorganisation
und Attributtabelle in einer gemeinsamen Arbeitsoberfläche.

Das Projekt richtet sich an Anwenderinnen und Anwender, die räumliche Daten ohne eine
klassische Desktop-GIS-Installation untersuchen und bearbeiten möchten. hGIS wird
fortlaufend gepflegt und entlang praktischer GIS-Anforderungen weiterentwickelt.

## Funktionen

- Projekte anlegen, duplizieren, öffnen und verwalten
- Shapefiles, GeoPackages, GeoJSON- und CSV-Dateien importieren
- Koordinatenbezug und Zeichenkodierung vor dem Import prüfen
- Layer ordnen, umbenennen, ein- und ausblenden sowie löschen
- Sichtbarkeit abhängig vom Kartenmaßstab festlegen
- Sachdaten in einer Attributtabelle anzeigen, filtern und sortieren
- Objekte gemeinsam in Karte und Attributtabelle auswählen
- Attribute einzelner Kartenobjekte abfragen
- Punkte, Linien und Flächen zeichnen, verschieben und löschen
- Beim Digitalisieren auf Stützpunkte, Schnittpunkte und Kanten einrasten
- Layer einheitlich, kategorisiert oder abgestuft darstellen
- Kartenobjekte anhand ihrer Attribute beschriften
- Strecken und Flächen in der Karte messen, mit laufender Anzeige beim Zeichnen
- Hintergrundkarte je Projekt wählen: OpenStreetMap, eine helle oder dunkle Variante
  davon, OpenTopoMap oder gar keine

### Nur über die Programmierschnittstelle

Der Layer-Export nach GeoJSON ist im Backend fertig, hat aber **noch keine Bedienung in
der Oberfläche** — weder für den ganzen Layer noch für eine Auswahl. Erreichbar ist er
direkt über die API:

```
GET  /api/layers/{layerId}/export.geojson             ganzer Layer
GET  /api/layers/{layerId}/export.geojson?fids=1,2,3  nur diese Objekte
POST /api/layers/{layerId}/export.geojson             dieselbe Auswahl als JSON-Rumpf,
                                                      für Auswahlen, die nicht in eine URL passen
```

Die Antwort ist eine `FeatureCollection` nach RFC 7946: Geometrien in EPSG:4326,
Attribute unter ihren ursprünglichen Feldnamen, jedes Objekt mit seiner `fid`. Ein leer
übergebener Parameter bedeutet ausdrücklich „nichts auswählen“ und liefert eine leere
Datei — nicht den ganzen Layer. Der Export nach GeoPackage fehlt noch.

## Fachliches Konzept

Ein Projekt bildet den Arbeitskontext. Es umfasst die enthaltenen Layer, deren
Reihenfolge und Darstellung, die gewählte Hintergrundkarte sowie den zuletzt betrachteten
Kartenausschnitt.

Jeder Layer verbindet Geometrien mit ihren Sachdaten. Die Karte zeigt die räumliche
Verteilung, während die Attributtabelle denselben Bestand tabellarisch zugänglich macht.
Auswahl und Filter gelten deshalb über beide Ansichten hinweg.

Änderungen an Geometrien werden zunächst in einer Bearbeitungssitzung gesammelt. Sie
können vor dem Speichern geprüft, zurückgenommen oder vollständig verworfen werden.
Fangfunktionen unterstützen dabei das passgenaue Erstellen zusammenhängender Geometrien.