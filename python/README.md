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
alt = df[df.pflanzjahr < 1950]

# Richtig: der Server filtert, Python bekommt nur die Treffer
alt = layer.where("pflanzjahr < 1950").to_dataframe()
```

Daraus folgt die verzögerte Auswertung. `where()`, `bbox()`, `search()` und
`order_by()` bauen nur auf. Sie schicken nichts.

`count()`, `fids()`, `to_dataframe()` und das Durchlaufen führen aus.

```python
q = layer.where("pflanzjahr > 1990")       # keine Anfrage
q = q.bbox(9.9, 53.5, 10.1, 53.6)          # keine Anfrage
q = q.order_by("pflanzjahr", desc=True)    # keine Anfrage

q.count()                                  # genau eine Anfrage
```

Jeder Baustein gibt eine neue Abfrage zurück. `eng = weit.where(...)` lässt
`weit` unverändert.

## Die Oberfläche

### Verbindung

| Aufruf | Ergebnis |
|---|---|
| `hgis.connect(url)` | `Client`, Standard `http://localhost:8080` |
| `hgis.connect(url, client_id=...)` | benennt den Schreiber, siehe unten |
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
| `layer.field(name)` | ein Feld, auch nach Feld-Id |
| `layer.ambiguous_names()` | Namen, die zwei Felder treffen |
| `layer.reference(feld)` | die eindeutige Schreibweise eines Felds |
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
layer.where('"Gattung Deutsch" IS NOT NULL')
layer.where("bezirk IN ('Wandsbek', 'Altona')")
layer.where("pflanzjahr > 1990 AND bezirk = 'Wandsbek'")
layer.where("fid IN (12, 47, 108)")
```

Setzen Sie Feldnamen mit Leerzeichen oder Umlauten in doppelte Anführungszeichen.
Setzen Sie Werte in einfache Anführungszeichen.

Der Server versteht `= <> != < <= > >=`, `LIKE`, `ILIKE`, `IS [NOT] NULL`,
`IN`, `AND`, `OR`, `NOT` und Klammern.

## Mehrdeutige Feldnamen

Ein Feld hat drei Namen. Nur einer davon trifft immer genau ein Feld.

| Bezeichner | Beispiel | Eindeutig? |
|---|---|---|
| Anzeigename | `Stammumfang` | nein |
| Spaltenname | `stammumfang_z` | nein |
| Feld-Id | `019ff731-1f15-7f4f-...` | ja |

Ein Import kann zwei Felder erzeugen, die sich einen Namen teilen. Im
Straßenbaumkataster trägt `Stammumfang Quelle` die Spalte `stammumfang`, und
`Stammumfang` trägt `stammumfang_z`. Das Wort `stammumfang` trifft damit zwei
Felder. Der Server lehnt es ab und nennt beide.

Die Bibliothek hilft Ihnen dabei:

```python
layer.ambiguous_names()          # {'stammumfang', 'kronendurchmesser'}
layer.field("stammumfang")       # UnknownNameError, nennt beide Felder und Ids
layer.field("stammumfang_z")     # findet das Feld
layer.reference(feld)            # die Schreibweise, die genau dieses Feld trifft
```

`describe()` markiert solche Felder und zeigt ihre Id:

```
Stammumfang (text)  mehrdeutig, Id 019ff731-1f15-7f4f-ba6a-804ecd372cd5  leer 0.5%
```

Nutzen Sie `layer.reference(feld)`, wenn Sie einen Feldnamen in einen Filter
oder eine Sortierung schreiben. `describe()` tut das bereits von sich aus.

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
| `ReadOnlyError` | Die Anfrage würde Daten ändern. Diese Stufe liest nur. |
| `InvalidClientIdError` | Der Client-Name passt nicht zu dem, was der Server annimmt. |

## Der Client-Name

Der Live-Kanal (`GET /api/events`) meldet, dass sich der Arbeitsstand eines
Projekts geändert hat. Er nennt dabei den Namen des Schreibers.

Wer seinen eigenen Namen liest, kennt den Stand schon und ignoriert die
Meldung. Deshalb schickt die Bibliothek beim Schreiben den Kopf
`X-Hgis-Client` mit.

```python
client = hgis.connect()
client.client_id                       # 'hgis-python-2d2dc2ed1874'

client = hgis.connect(client_id="agent-a")
```

Sie setzen den Namen auf drei Wegen. Der obere gewinnt:

| Weg | Beispiel |
|---|---|
| Parameter | `hgis.connect(client_id="agent-a")` |
| Umgebungsvariable | `HGIS_CLIENT_ID=agent-a` |
| Vorgabewert | je Prozess neu erzeugt |

**Geben Sie zwei gleichzeitig laufenden Programmen zwei Namen.** Sonst hält
jedes die Änderung des anderen für sein eigenes Echo und übergeht sie. Dann
geht eine echte Änderung verloren.

Der Vorgabewert ist deshalb zufällig und nicht fest. Er bleibt über die
Laufzeit des Prozesses gleich.

Erlaubt sind 1 bis 64 Zeichen aus Buchstaben, Ziffern, Bindestrich und
Unterstrich. Die Bibliothek prüft den Namen, sobald Sie den Client bauen.

```python
>>> hgis.connect(client_id="mit leerzeichen")
hgis.errors.InvalidClientIdError: Ungültiger Client-Name: 'mit leerzeichen'.
Erlaubt sind 1 bis 64 Zeichen aus Buchstaben, Ziffern, Bindestrich und
Unterstrich.
```

Der Kopf reist nur beim Schreiben mit. Ein Lesevorgang erzeugt kein Ereignis,
also gibt es dort kein Echo.

## Diese Stufe schreibt nicht

Die Bibliothek lässt nur lesende Anfragen durch. Dazu kommt genau ein
Schreibweg: `project.select()` speichert die Auswahl.

Jede andere Anfrage lehnt sie ab, bevor sie den Server erreicht.

```python
>>> client._send("DELETE", "/api/layers/019fecb8-...")
hgis.errors.ReadOnlyError: Diese Stufe der Bibliothek liest nur.
DELETE /api/layers/019fecb8-... ist nicht vorgesehen. Erlaubt sind lesende
Anfragen und das Speichern der Auswahl über project.select().
```

Das ist kein Schloss. Wer schreiben will, bindet `httpx` ein und umgeht die
Bibliothek. Es schützt vor dem Versehen.

Der Schutz zählt gerade jetzt besonders. Das Backend hat Endpunkte zum Löschen
von Layern und Projekten. Einen Papierkorb gibt es noch nicht. Eine
versehentlich gesendete Löschung ist endgültig.

Die Prüfung sitzt in `ReadOnlyGuard`. Der Client legt sie um jeden Transport,
auch um einen, den Sie selbst übergeben. Damit führt jeder Weg zum Netz durch
sie hindurch.

Einen allgemeinen Schreibbefehl gibt es nicht mehr. `Client.put(pfad, körper)`
ist entfallen. An seiner Stelle steht `Client.save_view_state(projekt, zustand)`,
also die eine Handlung statt eines beliebig einsetzbaren Verbs.

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
alt = layer.where("pflanzjahr < 1950").fids()
project.select(alt)                  # der Nutzer sieht die Auswahl

project.selection()              # <hgis.Selection Layer='...' Objekte=4711>
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
ruff check .
```

Die Ruff-Konfiguration steht im `pyproject.toml`. Rufen Sie `ruff check .` ohne
Zusatzflaggen auf. Dann bekommt jeder dasselbe Ergebnis.

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

Die Bibliothek fragt nie mehr als 1000 Zeilen je Seite an. Das ist die
Obergrenze des Servers. Er lehnt einen größeren Wert ab.
