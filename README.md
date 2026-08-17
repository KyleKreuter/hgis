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
- Rund 1100 amtliche Datensätze aus dem Geoportal Hamburg direkt laden, ohne den Umweg über
  eine Datei, wahlweise nur den aktuellen Kartenausschnitt und nur ausgewählte Felder
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
- Layer als Heatmap darstellen, wahlweise nach einem Zahlenfeld gewichtet
- Kartenobjekte anhand ihrer Felder beschriften
- Strecken und Flächen in der Karte messen, mit laufender Anzeige beim Zeichnen
- Hintergrundkarte für Projekt oder einzelnen Layer wählen: OpenStreetMap, eine helle
  oder dunkle Variante davon, OpenTopoMap oder gar keine, dazu die Deckkraft einstellen
- Objekte per Rechteck auswählen, wahlweise berührte oder vollständig eingeschlossene,
  und die Auswahl mit Umschalt ergänzen oder mit Alt abziehen
- Einen Layer oder die aktuelle Auswahl als GeoJSON herunterladen
- Die Karte als PNG speichern, in A4 oder A3 mit 96, 150 oder 300 dpi, mit Titel,
  Nordpfeil, Maßstabsbalken und Quellenangabe
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

### Daten aus dem Geoportal Hamburg

Hamburg stellt seine amtlichen Geodaten offen bereit. hGIS holt sie direkt vom Dienst, ohne
dass Sie vorher eine Datei herunterladen. „Daten aus dem Geoportal Hamburg“ öffnet einen
Dialog mit rund 1100 Einträgen: Straßenbaumkataster, Spielplätze, Baudenkmale,
Schutzgebiete, Verwaltungsgrenzen, Elektro-Ladestandorte, Bevölkerungsdaten zu Stadtteilen
und vieles mehr.

Die Suche greift auf Namen und Behörde zu und findet auch Wortteile. Drei Filter engen die
Liste weiter ein: die Art des Datensatzes, das Thema und die herausgebende Behörde. Sie
tippen „baum“ und sehen sofort das Straßenbaumkataster.

Acht große Dienste stehen als ein Eintrag in der Liste. Sie führen viele Sammlungen, der
Dienst „xplan“ allein 247. Ein solcher Eintrag trägt die Zahl seiner Sammlungen neben dem
Namen. Wählen Sie ihn aus, dann zeigt hGIS rechts die Liste seiner Sammlungen mit einem
eigenen Suchfeld. Wählen Sie dort eine Sammlung. Danach sehen Sie deren Felder und
Objektzahl und können sie importieren.

Zu jedem Datensatz zeigt der Dialog vor dem Laden, was Sie bekommen: die Beschreibung der
Behörde, die Zahl der Objekte, alle Felder mit ihrem Typ und, wo der Dienst sie nennt, die
möglichen Werte eines Feldes. Dazu den Quellenvermerk mit Lizenz und Links auf den
Metadatensatz und auf den Datensatz im Geoportal.

Zwei Schalter begrenzen, was geladen wird. Sie sind zu Anfang aus:

- **Nur den aktuellen Kartenausschnitt.** Das Programm setzt den sichtbaren Bereich der
  Karte als Grenze und nennt sofort die neue Objektzahl. Aus 229.876 Bäumen werden so
  wenige hundert.
- **Felder auswählen.** Zunächst sind alle angehakt. Wer nur einzelne Felder braucht, hält
  die Attributtabelle damit schmal.

Ab 100.000 Objekten weist der Dialog auf die Menge hin und schätzt die Dauer. Eine feste
Obergrenze gibt es nicht: Sie entscheiden, ob Sie fortfahren. Der Fortschritt läuft danach
über dieselbe Anzeige wie beim Dateiimport.

Die Feldnamen erscheinen so, wie die Behörde sie benennt. Aus `kronendurchmesser_z` wird
„Kronendurchmesser“. Filtern können Sie weiterhin mit beiden Schreibweisen.

#### Woher die Daten stammen, steht dabei

Die Daten stehen unter der Datenlizenz Deutschland – Namensnennung 2.0. Die Lizenz erlaubt
das Verändern, Zusammenführen und Weitergeben, verlangt dafür aber die Nennung der Quelle.
hGIS erledigt das für Sie: Der Quellenvermerk steht unten rechts in der Karte, neben der
Nennung der Hintergrundkarte, und zwar für jeden sichtbaren Layer aus dem Geoportal. Die
Layer-Eigenschaften zeigen ihn vollständig, mit Lizenz, Metadatensatz und dem Zeitpunkt des
Abrufs.

Der Vermerk gehört zum einzelnen Layer, nicht zum Projekt. Ein Projekt kann Layer
verschiedener Behörden enthalten, und jede Behörde gibt vor, wie sie genannt werden möchte.

#### Was heute noch nicht geht

- Datensätze, die nur als Kartenbild vorliegen, erscheinen in der Liste, lassen sich aber
  noch nicht verwenden. Sie werden später als Hintergrundkarte einbindbar.
- Führt ein Dienst mehrere Sammlungen, erreicht hGIS bisher nur die erste.
- Die Objektzahl steht erst in den Einzelheiten, nicht schon in der Liste. Das
  Dienstverzeichnis nennt sie nicht, und über 500 Einzelabfragen beim Öffnen wären zu teuer.
- Der Katalog wird auf Knopfdruck aktualisiert, nicht selbsttätig.

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

### Heatmap

Eine Heatmap zeigt, wo sich Objekte häufen. Sie ist die vierte Darstellungsart, neben
Einzelsymbol, Kategorien und Abstufung, und sie steht auf jedem Layer zur Verfügung.

Ohne weitere Angabe zählt jedes Objekt gleich. Wählen Sie ein Zahlenfeld als Gewicht,
zählt ein Objekt mit hohem Wert stärker. Hat ein Layer keine Zahlenfelder, bleibt die
Heatmap trotzdem wählbar; sie zeigt dann die Dichte. Dazu stellen Sie den Radius ein
(Einflussbereich eines Punkts in Bildschirmpunkten), die Intensität und den Farbverlauf.
Eine Legende nennt, was das schwächste und das stärkste Ende bedeuten.

**Linien und Flächen werden dafür zu Punkten.** Der Server legt auf jede Linie Punkte in
gleichmäßigem Abstand und setzt in jede Fläche einen Punkt. Das hat zwei Folgen, die Sie
kennen sollten:

- Eine lange Linie trägt mehr bei als eine kurze. Das ist gewollt: Ein Straßenzug von
  sechs Kilometern soll schwerer wiegen als einer von fünfhundert Metern.
- Beim Hineinzoomen rücken die Punkte nicht enger zusammen, sondern es werden mehr. Auf
  dem Bildschirm bleibt ihr Abstand gleich, die Glättung innerhalb einer Linie also auch.
  Der Abstand zwischen zwei getrennten Objekten ist dagegen eine feste Entfernung im
  Gelände. Beim Hineinzoomen wächst er auf dem Bildschirm, und aus einem durchgehenden
  Band werden einzelne Flecken.

Der Server legt die Punkte nicht auf die Stützpunkte der Linie. Sonst zeigte die Karte,
wie fein jemand digitalisiert hat, und nicht den Sachverhalt.

### Einen Bereich zuschneiden

Ein Flächenlayer lässt sich als Maske verwenden. Er wirkt auf alle Layer, die im
Layerbaum über ihm liegen. Layer unter der Maske bleiben unberührt.

Unter „Zuschnitt für alles darüber“ im Aktionsmenü des Layers wählen Sie zwischen fünf
Möglichkeiten:

- **Kein Zuschnitt.** Die Vorgabe.
- **Nur innerhalb.** Die Karte zeigt jedes Objekt, das ganz innerhalb der Maske liegt. Sie
  zeigt es vollständig und schneidet nichts ab.
- **Nur innerhalb + geschnitten.** Die Karte zeigt von jedem Objekt den Teil, der
  innerhalb liegt. Sie schneidet an der Kante durch.
- **Nur außerhalb.** Die Karte zeigt jedes Objekt, das die Maske nirgends berührt. Wieder
  vollständig.
- **Nur außerhalb + geschnitten.** Der Bereich innerhalb der Maske bleibt frei. Objekte an
  der Kante behalten ihren äußeren Teil.

Die beiden ungeschnittenen Modi zeigen nur eindeutige Fälle. Ein Objekt, das über die
Kante ragt, erscheint in keinem von beiden. Für diese Objekte sind die geschnittenen Modi
da: Sie zeigen genau den Teil, der auf der gewählten Seite liegt.

Eine Maske aus mehreren Flächen wirkt dabei als eine einzige Fläche. Ein Objekt, das über
die Naht zwischen zwei aneinandergrenzenden Maskenflächen reicht, liegt innerhalb.

Nur Flächenlayer und gemischte Layer taugen als Maske. Bei anderen Geometriearten ist die
Auswahl gesperrt und nennt den Grund.

Ein Projekt darf beliebig viele Masken haben. Auf einen Layer wirkt jede Maske, die unter
ihm liegt. Mehrere Masken grenzen dabei weiter ein: Ein Objekt bleibt sichtbar, wenn es
jede „innerhalb“-Maske berührt und keine „außerhalb“-Maske. Die Reihenfolge der Masken
untereinander ändert das Ergebnis nicht.

Ein Maskenlayer ist im Layerbaum an einem Zeichen erkennbar. Die geschnittenen Modi tragen
eine Schere, die ungeschnittenen einen Trichter. Der Tooltip nennt die Richtung. Das
Zeichen bleibt sichtbar, auch wenn Sie den Layer ausblenden. Die Maske wirkt nämlich
weiter: Oft will man die Grenze nicht sehen und trotzdem zuschneiden. Ohne dieses Zeichen
wäre der Zuschnitt nicht erklärbar.

Einen Layer nehmen Sie aus dem Zuschnitt, indem Sie ihn unter die Maske ziehen.

Der Zuschnitt betrifft nur die Karte. Attributtabelle, Auswahl und Export sehen weiterhin
alle Objekte. Das Rechteckwerkzeug fragt die Datenbank ab und wählt deshalb auch Objekte
aus, die eine Maske ausblendet. Bei aktivem Zuschnitt weist ein Zeichen in der
Werkzeugleiste darauf hin.

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

### Papierkorb und Änderungsprotokoll

Ein gelöschter Layer ist nicht weg. Er wandert in den Papierkorb: Der Katalogeintrag
bleibt, die Datentabelle bleibt, nur aus der Layerliste und der Karte verschwindet er. Der
Papierkorb zeigt zu jedem Eintrag den Namen, wann gelöscht wurde, von wem und wie viele
Objekte betroffen sind. Von dort holen Sie den Layer zurück oder löschen ihn endgültig.

Der Papierkorb leert sich nicht von selbst. Es gibt kein Ablaufdatum und keinen
Aufräumjob — nur Sie entscheiden, wann etwas wirklich verschwindet.

**Endgültiges Löschen ist der einzige Weg, der Daten wirklich vernichtet.** Er fragt vorher
nach und nennt dabei die Objektzahl.

Solange ein Layer im Papierkorb liegt, lässt er sich nicht mehr verändern — weder über die
Oberfläche noch über die Programmierschnittstelle. Sonst bekämen Sie beim Zurückholen
einen anderen Layer als den, den Sie gelöscht haben.

Für **einzelne Objekte** gibt es keinen Papierkorb. Ihre Rückfallebene ist das
Änderungsprotokoll: Es hält jeden Schreibvorgang fest — wer, wann, welcher Layer, wie
viele Objekte — und bei jedem Löschen zusätzlich die **vollständigen Zeilen** mit
Geometrie und allen Attributen. Daraus lässt sich ein gelöschtes Objekt wieder anlegen.
Das Protokoll erfasst auch, was nicht über die Oberfläche kommt: Import, Projektduplikat,
Zusammenführen und Teilen von Objekten, und jeden Zugriff aus einem Skript.

Ein Hinweis zur Größe: Die vollständigen Zeilen wachsen mit der Geometrie, etwa 30 Byte
je Stützpunkt. Ein einfacher Gebäudeumriss kommt auf rund 700 Byte je Objekt, ein
verwinkelter auf das Doppelte bis Dreifache.

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

### Die Karte als Bild

Der Knopf oben rechts auf der Karte speichert den aktuellen Ausschnitt als PNG. Sie
wählen einen Titel, ein Seitenformat (A4 oder A3, hoch oder quer, oder die Größe des
Kartenfensters) und die Auflösung (96, 150 oder 300 dpi). A4 quer bei 300 dpi ergibt
3508 x 2480 Pixel.

Auf dem Bild stehen der Titel, ein Maßstabsbalken, die Quellenangabe der Hintergrundkarte
und die Angaben der sichtbaren Geoportal-Layer. Der Nordpfeil erscheint nur, wenn die
Karte gedreht oder geneigt ist. Eine Legende fehlt noch.

Das Programm zeichnet das Bild auf einer zweiten, verborgenen Karte in der Größe der
Seite. Die sichtbare Karte bleibt unberührt und verliert keine Leistung. Der Maßstab des
Bildes ist ein anderer als der des Bildschirms, weil die Seite eine andere Form hat. Der
Balken gilt für das Bild.

Sehr große Formate lehnt das Programm ab und nennt die größte Bildgröße, die Ihre
Grafikkarte verarbeitet. Ein stillschweigend kleineres Bild gibt es nicht.

## Fachliches Konzept

Ein Projekt bildet den Arbeitskontext. Es umfasst die enthaltenen Layer, deren
Reihenfolge und Darstellung sowie den zuletzt betrachteten Kartenausschnitt. Das Projekt
legt außerdem eine Hintergrundkarte fest. Ein Layer kann diese überschreiben und eine
eigene Hintergrundkarte verwenden.

Jeder Layer verbindet Geometrien mit ihren Sachdaten. Die Karte zeigt die räumliche
Verteilung, während die Attributtabelle denselben Bestand tabellarisch zeigt. Auswahl und
Filter gelten deshalb über beide Ansichten hinweg.

Ein Layer kann zusätzlich eine Herkunft tragen: die Stelle, von der seine Daten stammen,
mit Lizenz und Abrufzeitpunkt. Layer aus dem Geoportal Hamburg bringen sie mit. Die
Herkunft hängt am einzelnen Layer, weil ein Projekt Daten aus mehreren Quellen verbinden
kann und jede Quelle ihre eigene Nennung verlangt.

Eine Bearbeitungssitzung sammelt Änderungen an Geometrien. Sie speichern die Änderungen
erst danach, und Sie können sie vorher prüfen, zurücknehmen oder vollständig verwerfen.
Einrasten unterstützt dabei das passgenaue Erstellen zusammenhängender Geometrien.