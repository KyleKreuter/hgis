# hgis

Python-Bibliothek für hGIS. Sie lesen damit Projekte, Layer und Objekte.

Diese Stufe liest nur. Sie ändert keine Daten. Die einzige Ausnahme ist die
Auswahl: `project.select()` schreibt den Arbeitsstand, nicht die Daten.

## Installation

```bash
pip install -e ".[http]"              # Kern plus HTTP-Boden
pip install -e ".[http,dataframe]"    # zusätzlich to_dataframe()
pip install -e ".[dev]"               # alles, plus pytest
```

`pandas` und `shapely` sind wahlfrei. Ohne sie arbeitet alles außer
`to_dataframe()`.

## Erste Schritte

```python
import hgis

client = hgis.connect()                       # Standard: http://localhost:8080
project = client.project("Wandsbek, Zuschnitt-Beispiel")
layer = project.layer("Straßenbaumkataster Hamburg")

print(layer.describe())
```

## Die Regel

Der Server rechnet. Python bekommt das Ergebnis.

```python
# Falsch: holt 229.876 Zeilen in den Speicher
df = layer.to_dataframe()
gross = df[df.stammumfang > 300]

# Richtig: der Server filtert, Python bekommt 2.310 Zeilen
gross = layer.where("stammumfang > 300").to_dataframe()
```

Daraus folgt die verzögerte Auswertung. `where()`, `bbox()`, `search()` und
`order_by()` bauen nur auf. Sie schicken nichts.

`count()`, `fids()`, `to_dataframe()` und das Durchlaufen führen aus.

```python
q = layer.where("pflanzjahr > 1990")       # keine Anfrage
q = q.bbox(9.9, 53.5, 10.1, 53.6)          # keine Anfrage
q = q.order_by("stammumfang", desc=True)   # keine Anfrage

q.count()                                  # genau eine Anfrage
```

Jeder Baustein gibt eine neue Abfrage zurück. `eng = weit.where(...)` lässt
`weit` unverändert.

## Die Oberfläche

### Verbindung

| Aufruf | Ergebnis |
|---|---|
| `hgis.connect(url)` | `Client`, Standard `http://localhost:8080` |
| `client.projects()` | `list[Project]` |
| `client.project(name_oder_kennung)` | `Project` |
| `client.layer(kennung)` | `Layer` |

### Projekt

| Aufruf | Ergebnis |
|---|---|
| `project.layers()` | `list[Layer]` |
| `project.layer(name_oder_kennung)` | `Layer` |
| `project.view()` | `View`: Mitte, Zoom, Ausschnitt, aktiver Layer |
| `project.selection()` | `Selection`: was der Nutzer angeklickt hat |
| `project.select(fids)` | setzt die Auswahl, macht den Layer aktiv |

### Layer

| Aufruf | Ergebnis |
|---|---|
| `layer.describe()` | vollständige Beschreibung, druckbar |
| `layer.fields()` | `list[Field]` |
| `layer.count()` | Objektzahl |
| `layer.feature(fid)` | ein Objekt mit allen Feldern |
| `layer.values(feld)` | Werte mit Häufigkeit |

### Abfrage

| Aufruf | Wirkung |
|---|---|
| `.where(ausdruck)` | baut auf, verknüpft mehrfach mit `AND` |
| `.search(text)` | baut auf, sucht in allen Textfeldern |
| `.bbox(minLng, minLat, maxLng, maxLat)` | baut auf, immer EPSG:4326 |
| `.order_by(feld, desc=True)` | baut auf |
| `.count()` | führt aus, eine Anfrage |
| `.fids()` | führt aus |
| `.to_dataframe()` | führt aus, braucht `pandas` |
| `for objekt in q` | führt aus, blättert selbst |

## Filterausdrücke

Der Server prüft den Ausdruck gegen die Felder des Layers.

```python
layer.where("pflanzjahr > 1990")
layer.where("gattung LIKE 'Quercus%'")
layer.where('"Kronendurchmesser" IS NOT NULL')
layer.where("bezirk IN ('Wandsbek', 'Altona')")
layer.where("pflanzjahr > 1990 AND bezirk = 'Wandsbek'")
```

Setzen Sie Feldnamen mit Leerzeichen oder Umlauten in doppelte Anführungszeichen.
Setzen Sie Werte in einfache Anführungszeichen.

Der Server versteht `= <> != < <= > >=`, `LIKE`, `ILIKE`, `IS [NOT] NULL`,
`IN`, `AND`, `OR`, `NOT` und Klammern.

`fid` ist kein Feld. Sie können nicht danach filtern.

## Fehler nennen das Gültige

Die Bibliothek reicht die Meldung des Servers unverändert durch.

```python
>>> layer.where("hoehe > 10").count()
hgis.errors.ApiError: Unbekanntes Feld: hoehe. Verfügbar: gid, BaumID,
Baumnummer, Gattung, ...
```

Bei eigenen Fehlern nennt die Bibliothek ebenso die vorhandenen Namen.

```python
>>> project.layer("Baeume")
hgis.errors.UnknownNameError: Unbekannter Layer: Baeume.
Verfügbar: Straßenbaumkataster Hamburg.
```

Alle Fehler erben von `hgis.HgisError`.

| Fehler | Bedeutung |
|---|---|
| `ApiError` | Der Server antwortet mit einem Fehler. `str()` ist seine Meldung. |
| `NotFoundError` | Der Server kennt die Sache nicht (HTTP 404). |
| `TransportError` | Es kommt keine Antwort an. |
| `UnknownNameError` | Kein Projekt oder Layer trägt diesen Namen. |
| `MissingDependencyError` | Ein wahlfreies Paket fehlt. |

## describe()

`describe()` liefert alles in einem Aufruf. Ein Agent, der dreimal nachfragt,
macht dreimal so viele Fehler.

```python
print(layer.describe())
```

```
Layer 'Straßenbaumkataster Hamburg'
  Geometrie: MULTIPOINT   CRS: EPSG:25832   Objekte: 229.876
  Ausschnitt (EPSG:4326): 9.73144, 53.39753, 10.32701, 53.72818

  Felder (24):
    gid (bigint)  leer 0.0%  von 1 bis 229876
    Gattung (text)  leer 0.0%  häufig: 'Tilia / Linde' (54379), ...
    Pflanzjahr (bigint)  leer 0.4%  von 0 bis 2104
    ...

  Beispielzeilen (5):
    fid 1: gid=1, BaumID=10, Baumnummer='1', ...
```

Die Ausgabe liest sich gedruckt. Genau so landet sie im Kontext eines Agenten.

`describe()` setzt die Antwort aus mehreren Endpunkten zusammen. Es fragt den
Layer ab, eine Seite Objekte und eine Statistik je Feld.

Bei einem Layer mit vielen Feldern kostet das viele Anfragen. `describe(stats=False)`
lässt die Statistik weg und liefert nur Namen und Typen.

## Auswahl

Der Server speichert die Auswahl je Layer.

```python
gross = layer.where("stammumfang > 300").fids()
project.select(gross)                  # der Nutzer sieht die Auswahl

project.selection()                    # <hgis.Selection Layer='...' Objekte=2310>
```

`select()` macht den Layer zum aktiven Layer. Eine Auswahl in einem Layer, den
der Nutzer nicht sieht, ändert nichts für ihn.

`select()` erhält alles andere. Die Auswahl der anderen Layer, jede Sortierung
und jede gespeicherte Abfrage bleiben unverändert.

## Zwei Böden für den Transport

Die öffentliche Oberfläche ist synchron. Darunter liegen zwei Umsetzungen.

| Umgebung | Boden |
|---|---|
| CPython | `HttpxTransport`, auf `httpx` |
| Pyodide (Browser) | `PyodideTransport`, auf synchronem `XMLHttpRequest` |

Pyodide hat keine Sockets. Dort läuft `httpx` nur asynchron. Ein `await` vor
jedem Aufruf ist aber die Stelle, an der Agenten am häufigsten danebengreifen.

Die Auswahl geschieht selbsttätig. `hgis.transport.default_transport()` prüft,
ob der Interpreter Pyodide ist.

Nur `hgis/transport.py` spricht HTTP. Ein Test prüft das. Er liest den
Syntaxbaum jedes Moduls und schlägt fehl, sobald ein anderes Modul `httpx`,
`urllib.request`, `requests` oder `socket` einbindet.

`PyodideTransport` läuft in dieser Stufe ungeprüft. Es gibt keinen Browser in
der Testreihe.

Sie können den Boden ersetzen:

```python
client = hgis.connect("http://localhost:8080", transport=MeinTransport())
```

## Tests

```bash
cd python
python -m pytest
```

Die Tests laufen ohne Backend. Sie arbeiten gegen abgelegte Antworten unter
`tests/responses/`.

Ein Test verlangt einen laufenden Server. Er überspringt sich selbst, wenn
keiner antwortet.

```bash
HGIS_URL=http://localhost:8080 python -m pytest       # mit Server
python -m pytest -m "not live"                        # ohne
```

## Grenzen dieser Stufe

- Kein Schreiben von Objekten. Kein Anlegen, kein Löschen.
- Kein MCP-Server.
- Kein Editor im Browser.
- Kein Live-Kanal, keine Ereignisse.
- `to_dataframe()` überträgt GeoJSON. Arrow und GeoParquet sind eine Frage der
  Geschwindigkeit. Sie kommen später.
