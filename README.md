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

## Fachliches Konzept

Ein Projekt bildet den Arbeitskontext. Es umfasst die enthaltenen Layer, deren
Reihenfolge und Darstellung sowie den zuletzt betrachteten Kartenausschnitt.

Jeder Layer verbindet Geometrien mit ihren Sachdaten. Die Karte zeigt die räumliche
Verteilung, während die Attributtabelle denselben Bestand tabellarisch zugänglich macht.
Auswahl und Filter gelten deshalb über beide Ansichten hinweg.

Änderungen an Geometrien werden zunächst in einer Bearbeitungssitzung gesammelt. Sie
können vor dem Speichern geprüft, zurückgenommen oder vollständig verworfen werden.
Fangfunktionen unterstützen dabei das passgenaue Erstellen zusammenhängender Geometrien.