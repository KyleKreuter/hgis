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
- Werte einzelner Kartenobjekte abfragen
- Punkte, Linien und Flächen zeichnen, verschieben und löschen
- Beim Zeichnen auf Stützpunkte, Schnittpunkte und Kanten einrasten
- Layer einheitlich, kategorisiert oder abgestuft darstellen. Die Farbe wechselt dabei je
  Klasse. Größe und Strichbreite gelten für alle Klassen gleich
- Kartenobjekte anhand ihrer Felder beschriften
- Strecken und Flächen in der Karte messen, mit laufender Anzeige beim Zeichnen
- Hintergrundkarte für Projekt oder einzelnen Layer wählen: OpenStreetMap, eine helle
  oder dunkle Variante davon, OpenTopoMap oder gar keine, dazu die Deckkraft einstellen
- Objekte per Rechteck auswählen, wahlweise berührte oder vollständig eingeschlossene,
  und die Auswahl mit Umschalt ergänzen oder mit Alt abziehen
- Einen Layer oder die aktuelle Auswahl als GeoJSON herunterladen
- Sachdaten direkt in der Attributtabelle bearbeiten, mit der Tastatur und je Feldtyp
  passender Eingabe
- Einstellungen und Arbeitsstand bleiben erhalten und kommen beim nächsten Öffnen zurück

### Was das Programm sich merkt

Alle Einstellungen eines Projekts liegen auf dem Server. Sie überstehen das Schließen des
Browsers und den Wechsel an einen anderen Rechner. Dazu gehören die Symbologie mit allen
Farben, Größen und Klassen, die Beschriftung, die Sichtbarkeit, die Reihenfolge der Layer
und der Kartenausschnitt.

Die Symbologie merkt sich auch, woraus die Klassen entstanden sind: die Methode, die Zahl
der Klassen und den Farbverlauf. Beim Öffnen stehen sie wieder da. Das Programm berechnet
die Klassen nur neu, wenn Sie eine dieser Angaben ändern.

Der Arbeitsstand kommt ebenfalls zurück: der zuletzt gewählte Layer, die Sortierung der
Attributtabelle, die Sucheingabe und die Auswahl. Vier Dinge fängt das Programm dabei ab:

- Ein wiederhergestellter Filter zeigt weniger Objekte, als der Layer enthält. Über der
  Attributtabelle steht dann ein Hinweis mit der Zahl der Treffer. Ein Klick löst den
  Filter.
- Zeigt ein gespeicherter Filter auf ein gelöschtes Feld, verwirft das Programm ihn und
  meldet das. Die Attributtabelle bleibt nutzbar.
- Zeigt die Auswahl auf gelöschte Objekte, fallen diese still weg.
- Ein Link auf einen Layer gewinnt über den gespeicherten Stand.

Ab 10.000 ausgewählten Objekten speichert das Programm die Auswahl nicht mehr. Es zeigt
das an. Die Fensteraufteilung bleibt im Browser, denn sie beschreibt einen Bildschirm und
kein Projekt.

### Eigene Layer anlegen

Ein Layer muss nicht aus einer Datei stammen. „Neuer Layer“ legt einen leeren Layer an.
Sie geben dabei einen Namen, die Geometrieart und beliebig viele Felder mit passendem Typ
an. Danach zeichnen Sie sofort hinein. Einrasten, Rückgängigmachen und alle anderen
Funktionen wirken wie bei importierten Daten.

Die Geometrieart legt fest, was der Layer aufnimmt: Punkte, Linien oder Flächen. Wählen
Sie „gemischt“, wenn ein Layer alle drei Geometriearten nebeneinander enthalten soll. Sie
legen die Geometrieart nicht ohne Grund fest: Sie schützt den Layer vor falschen
Objekten. In einen Layer „Bäume“ lässt sich dann keine Fläche zeichnen. Wenn Sie diese
Bindung nicht wollen, wählen Sie „gemischt“.

Sie können Felder auch nachträglich verwalten. „Felder verwalten“ im Aktionsmenü des
Layers legt neue Felder an, benennt vorhandene um und löscht sie. Das funktioniert bei
importierten Layern genauso wie bei selbst angelegten. Ein neues Feld bleibt bei
bestehenden Objekten zunächst ohne Wert.

Vor dem Löschen eines Feldes fragt das Programm nach und nennt die Folgen: wie viele
Objekte einen Wert in diesem Feld haben, und ob eine Einfärbung oder Beschriftung darauf
aufbaut. Falls ja, setzt das Programm die Einfärbung oder Beschriftung beim Löschen
zurück. Ohne diesen Schritt ließe sich die Symbologie des Layers danach nicht mehr
speichern. Solange am Layer ungespeicherte Änderungen offen sind, ist das Löschen
gesperrt.

Der Feldtyp bleibt unveränderlich. Das Programm zeigt ihn an, bietet ihn aber nicht zur
Bearbeitung an. Eine Umwandlung würde an jedem Wert scheitern, der sich nicht überführen
lässt, und eine halb umgestellte Spalte hinterlassen.

### Hintergrundkarte wählen

Jeder Layer kann sich eine eigene Hintergrundkarte merken. Wird er zum aktiven Layer,
wechselt die Hintergrundkarte mit. Hat der Layer keine eigene, gilt die Hintergrundkarte
des Projekts. Zusätzlich lässt sich die Deckkraft der Hintergrundkarte einstellen,
getrennt für Projekt und Layer.

Beim Wählen sehen Sie, wofür Ihre Wahl gilt: für das Projekt oder nur für den aktiven
Layer. Das Programm errät es nicht aus der Lage. Sonst würde dasselbe Bedienelement mal
das eine und mal das andere ändern, ohne dass Sie sähen, was gerade gilt. Hat der aktive
Layer eine eigene Karte, zeigt das Programm das an. Mit einem Klick kehren Sie zur Karte
des Projekts zurück. Das Aktionsmenü eines Layers führt denselben Eintrag, für den
direkten Weg ohne Umweg über die Karte.

Die Deckkraft betrifft die Hintergrundkarte selbst, nicht die Objekte darauf. Für die
Objekte gibt es die Deckkraft in der Symbologie. Bei „Keine Hintergrundkarte“ entfällt der
Regler, denn es gibt nichts zu regeln. Eine Karte mit verringerter Deckkraft lässt den
Anwendungshintergrund durchscheinen. Im dunklen Erscheinungsbild sieht sie deshalb anders
aus als im hellen.

### Einen Bereich zuschneiden

Ein Flächenlayer lässt sich als Maske verwenden. Er wirkt auf alle Layer, die im
Layerbaum über ihm liegen. Layer unter der Maske bleiben unberührt.

Unter „Zuschnitt für alles darüber“ im Aktionsmenü des Layers wählen Sie zwischen drei
Möglichkeiten:

- **Kein Zuschnitt.** Die Vorgabe.
- **Nur innerhalb zeigen.** Die Karte zeigt die oberen Layer nur innerhalb der Flächen
  der Maske.
- **Nur außerhalb zeigen.** Umgekehrt: Der Bereich innerhalb der Maske bleibt frei.

Ein Objekt, das über die Kante ragt, wird an der Kante durchgeschnitten. Es verschwindet
also nicht ganz, und es ragt auch nicht heraus. Beide Richtungen zusammen ergeben wieder
den vollständigen Bestand.

Nur Flächenlayer und gemischte Layer taugen als Maske. Bei anderen Geometriearten ist die
Auswahl gesperrt und nennt den Grund.

Ein Projekt hat höchstens eine Maske. Markieren Sie eine zweite, verliert die erste ihre
Markierung. Das Programm meldet das.

Ein Maskenlayer ist im Layerbaum an einer Schere erkennbar. Das Zeichen unterscheidet
beide Richtungen, und der Tooltip nennt sie. Es bleibt sichtbar, auch wenn Sie den Layer
ausblenden. Die Maske wirkt nämlich weiter: Oft will man die Grenze nicht sehen und
trotzdem zuschneiden. Ohne dieses Zeichen wäre der Zuschnitt nicht erklärbar.

Einen Layer nehmen Sie aus dem Zuschnitt, indem Sie ihn unter die Maske ziehen.

Der Zuschnitt betrifft nur die Karte. Attributtabelle, Auswahl und Export sehen weiterhin
alle Objekte. Das Rechteckwerkzeug fragt die Datenbank ab und wählt deshalb auch Objekte
außerhalb der Maske aus, die Sie gar nicht sehen. Bei aktivem Zuschnitt weist ein Zeichen
in der Werkzeugleiste darauf hin.

### Sachdaten bearbeiten

Die Attributtabelle hat einen eigenen Bearbeitungsmodus. Er ist vom Zeichenmodus an der
Karte getrennt. Beide schließen sich aus: Wenn Sie den einen einschalten, fragt das
Programm nach, bevor es ungespeicherte Änderungen des anderen verwirft.

Jede Spalte bekommt die Eingabe, die zu ihrem Typ passt: ein Datumsfeld für ein Datum,
eine Auswahl für Ja/Nein, ein Zahlenfeld für Zahlen. Ein geleertes Feld bedeutet
ausdrücklich NULL, nicht den leeren Text. Bei Ja/Nein gibt es NULL als dritte
Möglichkeit. Sie arbeiten dabei mit der Tastatur: Pfeiltasten bewegen den Fokus, Enter
öffnet eine Zelle, ebenso das Tippen eines Zeichens. Enter springt danach eine Zeile
tiefer, Tab eine Spalte weiter, Escape verwirft die Eingabe.

Ungespeicherte Änderungen gehen nicht unbemerkt verloren. Wenn Sie die Ansicht verlassen,
den Layer wechseln, zurückgehen oder den Tab schließen, fragt das Programm nach. Es nennt
dabei die Zahl der offenen Änderungen. Einen Layer, an dem Sie gerade arbeiten, können Sie
außerdem nicht löschen.

Änderungen liegen zunächst nur im Arbeitsspeicher. Ein Zähler zeigt die offenen
Änderungen an. Speichern und Verwerfen sind getrennte Aktionen. Beim Speichern prüft der
Server, ob eine andere Person die Zeile inzwischen geändert hat. Falls ja, lehnt der
Server den ganzen Vorgang ab. So überschreibt er keine fremde Arbeit.

### Suchen und filtern

Über der Attributtabelle liegt ein Eingabefeld mit zwei Betriebsarten. **Suchen** nimmt
einen beliebigen Begriff. Es findet ihn in allen Textspalten des Layers, auch als
Wortteil und unabhängig von Groß- und Kleinschreibung. Dafür brauchen Sie kein
Vorwissen. **Filtern** erwartet einen Ausdruck und fragt damit genauer:

```
nutzungsart = 'Wohnen' AND baujahr > 1990
strasse LIKE 'Alster%' OR strasse IS NULL
gebaeudetyp IN ('Reihenhaus', 'Doppelhaus') AND NOT denkmal
```

Sie schalten die Betriebsart über das Symbol links im Feld um. Das Programm errät die
Betriebsart bewusst nicht aus der Eingabe. Ein Suchbegriff darf deshalb wie ein Operator
aussehen, ohne dass sich das Verhalten ändert.

Mit einem Klick wählen Sie alle Treffer vollständig aus, auch tausende Objekte. Von Hand
würde das niemand anklicken. Die Treffer stammen aus der Datenbank, nicht aus der
geladenen Tabellenseite. Deshalb sind sie vollständig. Ab tausend Objekten fragt das
Programm vorher nach.

### Auswählen und exportieren

Das Rechteckwerkzeug fragt die Objekte in der Datenbank ab. Es liest sie nicht aus dem
gezeichneten Kartenbild. Der Unterschied ist wichtig: Vektorkacheln schneiden Geometrien
an ihren Rändern ab. Je nach Zoomstufe zeigen sie nicht alle Objekte. Eine Auswahl aus
dem Kartenbild wäre deshalb unvollständig, ohne dass Sie es merken. Wenn ein Rechteck
mehr als tausend Objekte umfasst, fragt die Anwendung vor dem Laden nach.

Der Export liegt im Aktionsmenü jedes Layers, einmal für den ganzen Layer und einmal für
die Auswahl. Er liefert eine `FeatureCollection` nach RFC 7946. Die Geometrien liegen in
EPSG:4326, die Felder behalten ihre ursprünglichen Namen, und jedes Objekt trägt seine
`fid`.

Beide Endpunkte lassen sich auch direkt ansprechen:

```
GET  /api/layers/{layerId}/export.geojson             ganzer Layer
GET  /api/layers/{layerId}/export.geojson?fids=1,2,3  nur diese Objekte
POST /api/layers/{layerId}/export.geojson             dieselbe Auswahl als JSON-Rumpf,
                                                      für Auswahlen, die nicht in eine URL passen
```

Dabei gilt: Ein leer übergebener Parameter bedeutet ausdrücklich „nichts auswählen“. Er
liefert eine leere Datei, nicht den ganzen Layer. Der Export nach GeoPackage fehlt noch.

## Fachliches Konzept

Ein Projekt bildet den Arbeitskontext. Es umfasst die enthaltenen Layer, deren
Reihenfolge und Darstellung sowie den zuletzt betrachteten Kartenausschnitt. Das Projekt
legt außerdem eine Hintergrundkarte fest. Ein Layer kann diese überschreiben und eine
eigene Hintergrundkarte verwenden.

Jeder Layer verbindet Geometrien mit ihren Sachdaten. Die Karte zeigt die räumliche
Verteilung, während die Attributtabelle denselben Bestand tabellarisch zeigt. Auswahl und
Filter gelten deshalb über beide Ansichten hinweg.

Eine Bearbeitungssitzung sammelt Änderungen an Geometrien. Sie speichern die Änderungen
erst danach, und Sie können sie vorher prüfen, zurücknehmen oder vollständig verwerfen.
Einrasten unterstützt dabei das passgenaue Erstellen zusammenhängender Geometrien.