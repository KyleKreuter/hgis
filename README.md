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
- Leere Layer anlegen und direkt hineinzeichnen, ohne den Umweg über eine Datei
- Shapefiles, GeoPackages, GeoJSON- und CSV-Dateien importieren
- Koordinatenbezug und Zeichenkodierung vor dem Import prüfen
- Layer ordnen, umbenennen, ein- und ausblenden sowie löschen
- Sichtbarkeit abhängig vom Kartenmaßstab festlegen
- Sachdaten in einer Attributtabelle anzeigen, durchsuchen, filtern und sortieren
- Alle Treffer einer Suche auf einmal auswählen und als Ganzes exportieren
- Objekte gemeinsam in Karte und Attributtabelle auswählen
- Attribute einzelner Kartenobjekte abfragen
- Punkte, Linien und Flächen zeichnen, verschieben und löschen
- Beim Digitalisieren auf Stützpunkte, Schnittpunkte und Kanten einrasten
- Layer einheitlich, kategorisiert oder abgestuft darstellen — Farbe je Klasse, Größe und
  Strichbreite für alle Klassen gemeinsam
- Kartenobjekte anhand ihrer Attribute beschriften
- Strecken und Flächen in der Karte messen, mit laufender Anzeige beim Zeichnen
- Hintergrundkarte je Projekt wählen: OpenStreetMap, eine helle oder dunkle Variante
  davon, OpenTopoMap oder gar keine
- Objekte per Rechteck auswählen, wahlweise berührte oder vollständig eingeschlossene,
  und die Auswahl mit Umschalt ergänzen oder mit Alt abziehen
- Einen Layer oder die aktuelle Auswahl als GeoJSON herunterladen
- Sachdaten direkt in der Attributtabelle bearbeiten, mit der Tastatur und je Feldtyp
  passender Eingabe

### Eigene Layer anlegen

Ein Layer muss nicht aus einer Datei stammen. „Neuer Layer“ legt einen leeren an — Name,
Geometrieart und beliebig viele Attributfelder mit dem jeweils passenden Typ. Danach lässt
sich sofort hineinzeichnen; alles Weitere, Einrasten und Rückgängig eingeschlossen,
funktioniert wie bei importierten Daten.

Die Geometrieart legt fest, was der Layer aufnimmt: Punkte, Linien, Flächen — oder
„gemischt“, wenn alles davon nebeneinander stehen soll. Die Festlegung auf eine Art ist
kein Formalismus, sondern ein Schutz: In einen Layer „Bäume“ lässt sich dann keine Fläche
zeichnen. Wer diese Bindung nicht will, wählt „gemischt“.

Attributfelder lassen sich auch nachträglich verwalten — „Felder verwalten“ im
Aktionsmenü des Layers legt neue an, benennt vorhandene um und löscht sie, bei
importierten Layern ebenso wie bei selbst angelegten. Bestehende Objekte bekommen bei
einem neuen Feld zunächst keinen Wert.

Vor dem Löschen wird gefragt, und die Frage nennt, worum es geht: wie viele Objekte einen
Wert in diesem Feld haben, und ob eine Einfärbung oder Beschriftung darauf aufbaut. Ist
das der Fall, wird sie beim Löschen zurückgesetzt — andernfalls ließe sich die Symbologie
des Layers anschließend gar nicht mehr speichern. Solange an dem Layer ungespeicherte
Änderungen offen sind, ist das Löschen gesperrt.

Der Feldtyp bleibt unveränderlich. Er wird angezeigt, aber nicht zur Bearbeitung
angeboten: Eine Umwandlung scheitert an jedem Wert, der sich nicht überführen lässt, und
hinterließe eine halb umgestellte Spalte.

### Sachdaten bearbeiten

Die Attributtabelle hat einen eigenen Bearbeitungsmodus, getrennt vom Digitalisieren an
der Karte. Beide schließen sich aus: Wer den einen einschaltet, wird gefragt, bevor
ungespeicherte Änderungen des anderen verworfen werden.

Jede Spalte bekommt die Eingabe, die zu ihrem Typ passt — ein Datumsfeld für ein Datum,
eine Auswahl für Ja/Nein, ein Zahlenfeld für Zahlen. Ein geleertes Feld bedeutet
ausdrücklich NULL und nicht den leeren Text; bei Ja/Nein gibt es NULL als dritte
Möglichkeit. Gearbeitet wird mit der Tastatur: Pfeiltasten bewegen den Fokus, Enter oder
Lostippen öffnet eine Zelle, Enter springt eine Zeile tiefer, Tab eine Spalte weiter,
Escape verwirft.

Ungespeicherte Änderungen gehen nicht unbemerkt verloren: Wer die Ansicht verlässt, den
Layer wechselt, zurückgeht oder den Tab schließt, wird gefragt — mit der Zahl der
Änderungen, um die es geht. Ein Layer, an dem gerade gearbeitet wird, lässt sich
außerdem nicht löschen.

Geändert wird zunächst nur im Arbeitsspeicher; ein Zähler zeigt die offenen Änderungen,
Speichern und Verwerfen sind getrennte Aktionen. Beim Speichern prüft der Server, ob die
Zeile inzwischen von jemand anderem geändert wurde, und lehnt in diesem Fall den ganzen
Vorgang ab, statt fremde Arbeit zu überschreiben.

### Suchen und filtern

Über der Attributtabelle liegt ein Eingabefeld mit zwei Betriebsarten. **Suchen** nimmt
einen beliebigen Begriff und findet ihn in allen Textspalten des Layers, unabhängig von
Groß- und Kleinschreibung und auch als Wortteil — dafür muss man nichts weiter wissen.
**Filtern** erwartet einen Ausdruck und kann dafür genauer fragen:

```
nutzungsart = 'Wohnen' AND baujahr > 1990
strasse LIKE 'Alster%' OR strasse IS NULL
gebaeudetyp IN ('Reihenhaus', 'Doppelhaus') AND NOT denkmal
```

Umgeschaltet wird über das Symbol links im Feld. Welche Betriebsart gilt, wird bewusst
nicht aus der Eingabe erraten: Ein Suchbegriff darf wie ein Operator aussehen, ohne dass
sich das Verhalten ändert.

Was die Einschränkung findet, lässt sich mit einem Klick vollständig auswählen — auch
tausende Objekte, die von Hand niemand anklicken würde. Die Treffer kommen aus der
Datenbank und nicht aus der geladenen Tabellenseite, sind also vollständig. Ab tausend
Objekten wird vorher gefragt.

### Auswählen und exportieren

Das Rechteckwerkzeug fragt die Objekte in der Datenbank ab, nicht im gezeichneten
Kartenbild. Das ist der Unterschied, der zählt: Vektorkacheln schneiden Geometrien an
ihren Rändern und zeigen je nach Zoomstufe nicht alles, was da ist — eine Auswahl aus dem
Bild wäre deshalb stillschweigend unvollständig. Umfasst ein Rechteck mehr als tausend
Objekte, fragt die Anwendung vor dem Laden nach.

Der Export liegt im Aktionsmenü jedes Layers, einmal für den ganzen Layer und einmal für
die Auswahl. Geliefert wird eine `FeatureCollection` nach RFC 7946: Geometrien in
EPSG:4326, Attribute unter ihren ursprünglichen Feldnamen, jedes Objekt mit seiner `fid`.

Beide Endpunkte lassen sich auch direkt ansprechen:

```
GET  /api/layers/{layerId}/export.geojson             ganzer Layer
GET  /api/layers/{layerId}/export.geojson?fids=1,2,3  nur diese Objekte
POST /api/layers/{layerId}/export.geojson             dieselbe Auswahl als JSON-Rumpf,
                                                      für Auswahlen, die nicht in eine URL passen
```

Dabei gilt: Ein leer übergebener Parameter bedeutet ausdrücklich „nichts auswählen“ und
liefert eine leere Datei — nicht den ganzen Layer. Der Export nach GeoPackage fehlt noch.

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