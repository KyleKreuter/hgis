# hgis

Python-Bibliothek für hGIS. Sie lesen und schreiben damit Projekte, Layer und
Objekte.

Ein `RequestGuard` prüft jede Anfrage, bevor sie den Server erreicht. Lesen
ist uneingeschränkt. Schreiben ist eine feste Liste: die Auswahl speichern,
einen Layer anlegen/ändern/löschen/wiederherstellen/endgültig löschen, ein
Feld anlegen/löschen und ein Stapel Objekt-Änderungen. Jede andere Anfrage
lehnt die Bibliothek ab, bevor sie den Server erreicht -- siehe
[„Was der Wächter durchlässt"](#was-der-wächter-durchlässt).

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
| `project.create_layer(name, geometrietyp, fields=...)` | legt einen leeren Layer an, gibt ihn zurück |

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
| `layer.update(name=..., visible=..., ...)` | ändert den Layer, gibt sich selbst zurück |
| `layer.delete()` | Layer in den Papierkorb, gibt `TrashEntry` zurück (oder `None`, siehe unten) |
| `layer.restore()` | Layer aus dem Papierkorb zurück |
| `layer.purge()` | Layer und Daten endgültig löschen -- **unwiderruflich**, gibt `TrashEntry` zurück (oder `None`, siehe unten) |
| `layer.create_field(name, typ)` | ein neues Feld, siehe [Felder anlegen und löschen](#felder-anlegen-und-löschen) |
| `layer.delete_field(feld)` | löscht ein Feld -- **unwiderruflich** |
| `layer.insert(geometrie, eigenschaften=...)` | ein neues Objekt, gibt dessen Fid zurück |
| `layer.insert_many(objekte)` | mehrere neue Objekte in einer Transaktion |
| `layer.update_feature(fid, row_version, ...)` | ändert ein Objekt |
| `layer.delete_features(fids)` | löscht benannte Objekte -- siehe [Was unwiederbringlich ist](#was-unwiederbringlich-ist) |
| `layer.edit(creates=..., updates=..., deletes=...)` | ein Stapel aller drei, eine Transaktion |

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

## Schreiben

Jeder Schreibvorgang wirkt sofort. Es gibt hier keine verzögerte Auswertung
wie bei `Query` -- `layer.insert(...)` sendet, sobald Sie es aufrufen.

### Layer anlegen, ändern, löschen

```python
neu = project.create_layer(
    "Bäume", "MULTIPOINT",
    fields={"Gattung": "TEXT", "Pflanzjahr": "INTEGER"},
)

neu.update(name="Straßenbäume", visible=True)

eintrag = neu.delete()   # in den Papierkorb
neu.restore()             # zurück, liest den Layer neu
neu.purge()                # endgültig -- siehe unten
```

`create_layer()` kennt neun Feldtypen: `TEXT`, `INTEGER`, `BIGINT`, `DOUBLE`,
`NUMERIC`, `BOOLEAN`, `DATE`, `TIME`, `TIMESTAMP`. Ein unbekannter Typ kommt
als Serverfehler zurück, der die gültigen Typen nennt.

`delete()` und `purge()` melden, was geschehen ist: `TrashEntry` (`id`,
`name`, `deleted_at`, `feature_count`, `deleted_by`) -- **sofern der Server
das mitschickt.** Heute antworten beide Endpunkte noch mit einem leeren 204,
also ist `eintrag` zurzeit `None`. Das ist bewusst kein geratener Wert: die
Bibliothek erfindet keine Objektzahl aus dem, was sie vor dem Aufruf über den
Layer wusste, weil das inzwischen nicht mehr stimmen muss. Sobald der Server
eine Antwort im Format von `LayerDtos.TrashEntry` mitschickt, füllt sich
`eintrag` von selbst -- ohne eine weitere Änderung in dieser Bibliothek.

### Felder anlegen und löschen

```python
feld = layer.create_field("Baujahr", "INTEGER")
layer.delete_field(feld)              # auch nach Name oder Id: layer.delete_field("Baujahr")
```

### Objekte schreiben

```python
fid = layer.insert(
    {"type": "Point", "coordinates": [9.99, 53.55]},
    {"Gattung": "Tilia", "Pflanzjahr": 2024},
)

fids = layer.insert_many([
    hgis.NewFeature({"type": "Point", "coordinates": [9.98, 53.54]}, {"Gattung": "Acer"}),
    hgis.NewFeature({"type": "Point", "coordinates": [9.97, 53.53]}, {"Gattung": "Quercus"}),
])

objekt = layer.feature(fid)
layer.update_feature(fid, objekt.row_version, properties={"Pflanzjahr": 2025})

layer.delete_features([fid])          # nennt jede Kennung einzeln, siehe unten
```

`row_version` kommt von `Feature.row_version` (`GET .../features/{fid}` oder
jede Abfrage). Stimmt sie nicht mehr mit der Serverzeile überein, wirft die
Bibliothek `hgis.ConflictError`; `error.current` trägt die Zeile, wie sie
gerade auf dem Server steht.

Alle vier Aufrufe sind Kurzformen von `layer.edit(creates=..., updates=...,
deletes=...)`, das einen ganzen Stapel in einer Transaktion sendet und dabei
`repair_invalid=True` annimmt, um eine ungültige Geometrie zu reparieren
(`ST_MakeValid`) statt sie abzulehnen.

**Kein `layer.delete_all_features()`.** Es gibt keinen Aufruf, der Objekte
ohne benannte Kennung löscht. Wer eine ganze Auswahl löschen will, nennt sie
ausdrücklich: `layer.delete_features(layer.fids())`. Ein vergessener Filter
kann so nie zu "alles löschen" werden.

**Eine Zeichenkette ist keine Liste, auch wenn Python sie so behandelt.**
`layer.delete_features("123")` -- fast immer als die eine Kennung 123 gemeint
-- würde ohne eigene Prüfung Zeichen für Zeichen zerlegt und die Objekte 1, 2
und 3 löschen. Die Bibliothek lehnt eine Zeichenkette hier mit
`hgis.InvalidArgumentError` ab, statt sie stillschweigend so zu lesen.

### Was unwiederbringlich ist

| Vorgang | Rückgängig zu machen? |
|---|---|
| `layer.delete()` | Ja -- `layer.restore()`, solange der Papierkorb nicht geleert wurde |
| `layer.purge()` | **Nein.** Es gibt keinen Papierkorb hinter diesem Aufruf |
| `layer.delete_field(...)` | **Nein**, nicht über diese Bibliothek |
| `layer.delete_features(...)` / `layer.edit(deletes=...)` | **Nein**, nicht über diese Bibliothek |

Für gelöschte Felder und Objekte führt der Server ein Änderungsprotokoll
(`GET /api/projects/{id}/changes`), das bei einer Objektlöschung die
vollständige Zeile mitschreibt -- Geometrie und Attribute. Das ist die
einzige Rückfallebene: Wiederherstellen heißt, die Zeile von dort zu lesen
und mit `layer.insert(...)` neu anzulegen. Diese Bibliothek liest das
Protokoll nicht für Sie; `client.get(f"/api/projects/{project_id}/changes")`
funktioniert bereits, denn Lesen ist uneingeschränkt.

### Was der Wächter durchlässt

`RequestGuard` prüft jede Anfrage gegen eine feste Liste, bevor sie den
Transport erreicht -- egal ob sie über `client.get(...)` kommt oder über
`client._transport.request(...)` direkt. Erlaubt sind lesende Anfragen
(jedes `GET`) sowie genau die Schreibvorgänge oben. Alles andere -- Layer neu
ordnen, Objekte teilen oder zusammenführen, ein Projekt löschen -- lehnt die
Bibliothek mit `hgis.GuardError` ab, bevor der Server sie sieht.

```python
>>> client._send("PUT", f"/api/projects/{pid}/layers/order", json={})
hgis.errors.GuardError: PUT /api/projects/.../layers/order ist nicht
vorgesehen. Erlaubt sind lesende Anfragen, project.select() und die
Schreibwege dieser Stufe: ...
```

Das ist kein Schloss. Wer schreiben will, bindet `httpx` ein und umgeht die
Bibliothek. Es schützt vor dem Versehen -- einer Anfrage, die niemand
absichtlich gestellt hat.

**`X-Hgis-Client` reist bei jedem Schreibvorgang mit**, auch bei den neuen.
Der Server schreibt ihn ins Änderungsprotokoll; ohne ihn steht dort
"unbekannt". Siehe [Der Client-Name](#der-client-name).

### Umleitungen

Die Bibliothek folgt einer Umleitung selbst und prüft jeden Sprung erneut --
das gilt jetzt für jeden erlaubten Schreibweg, nicht nur für die Auswahl.

Das HTTP-Paket folgt nicht mehr von allein. Es täte das innerhalb desselben
Aufrufs, den die Prüfung schon durchgelassen hat. Bei 307 und 308 behält die
Anfrage dabei Methode und Inhalt -- ein erlaubtes `DELETE` auf einen Layer
würde so als `DELETE` auf einen verbotenen Pfad wieder hinausgehen, ungeprüft,
wenn die Bibliothek nicht selbst nachsähe.

Eine Umleitung darf den Server nicht wechseln. Sonst schickt eine
eingeschleuste Umleitung Ihre Anfrage samt Kopfzeilen an einen fremden
Rechner.

Im Browser gilt das nicht. `XMLHttpRequest` folgt Umleitungen selbst und
lässt sich davon nicht abbringen. Dort schützt die Herkunftsregel des
Browsers -- wie beim Ereigniskanal, siehe unten.

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
| `ConflictError` | `row_version` stimmt nicht mehr (HTTP 409); `error.current` trägt die Zeile |
| `TransportError` | Es kommt keine Antwort an. |
| `UnknownNameError` | Kein Projekt oder Layer trägt diesen Namen. |
| `MissingDependencyError` | Ein wahlfreies Paket fehlt. |
| `GuardError` | Die Anfrage ist nicht vorgesehen, siehe [Was der Wächter durchlässt](#was-der-wächter-durchlässt). |
| `InvalidArgumentError` | Ein Argument hat nicht die erwartete Form, z. B. eine Zeichenkette statt einer Liste bei `delete_features(...)`. |
| `UnsafeTransportError` | Ein übergebener `httpx.Client` hat `follow_redirects=True` und wird deshalb abgelehnt, siehe [Zwei Böden für den Transport](#zwei-böden-für-den-transport). |
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

**Nach einem `fork` bekommt das Kind einen eigenen Namen.** Das gilt auch für
einen Client, den Sie vor dem `fork` gebaut haben. Ein Kind erbt das
Speicherabbild der Eltern, also auch einen einmal berechneten Namen. Vier
Arbeiter eines `multiprocessing.Pool` schrieben sonst unter einem Namen.

Einen Namen, den Sie selbst gesetzt haben, behält das Kind. Sie haben ihn
gewählt, und ein `fork` hebt Ihre Wahl nicht auf.

**Mehrere Threads bekommen denselben Namen.** Die Berechnung läuft unter einer
Sperre und wirkt nur einmal. Ohne sie berechnen mehrere Threads gleichzeitig
je einen eigenen Namen, und ein Prozess schreibt unter mehreren.

Erlaubt sind 1 bis 64 Zeichen aus Buchstaben, Ziffern, Bindestrich und
Unterstrich.

Die Bibliothek prüft den Namen, sobald Sie den Client bauen. Sie prüft ihn
außerdem bei jedem Zugriff, denn Sie können `HGIS_CLIENT_ID` auch danach noch
ändern. Ein unbrauchbarer Name geht so nie als Kopfzeile hinaus.

```python
>>> hgis.connect(client_id="mit leerzeichen")
hgis.errors.InvalidClientIdError: Ungültiger Client-Name: 'mit leerzeichen'.
Erlaubt sind 1 bis 64 Zeichen aus Buchstaben, Ziffern, Bindestrich und
Unterstrich.
```

Der Kopf reist nur beim Schreiben mit. Ein Lesevorgang erzeugt kein Ereignis,
also gibt es dort kein Echo.

Das gilt auch nach einer Umleitung. Schreibt die Bibliothek eine Anfrage auf
`GET` um, weil der Server mit 301, 302 oder 303 antwortet, fällt der Kopf
zusammen mit dem Inhalt weg. Bei 307 und 308 bleibt die Anfrage ein
Schreibvorgang, also bleibt auch der Kopf.

## Verschlüsselung

Die Standardadresse ist `http://localhost:8080`, also unverschlüsselt.

Das ist für einen lokalen Server richtig. Auf der Loopback-Schnittstelle gibt
es keine Leitung, auf der jemand mithören kann.

Sobald Sie über ein echtes Netz zugreifen, gilt das nicht mehr. Verwenden Sie
dann `https://`.

Einen allgemeinen Schreibbefehl gibt es nicht. `Client.put(pfad, körper)` hat
es nie gegeben, auch jetzt nicht, wo die Bibliothek wirklich schreibt -- jeder
Schreibweg hat einen eigenen, benannten Aufruf, siehe
[Was der Wächter durchlässt](#was-der-wächter-durchlässt).

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

**`HttpxTransport` lehnt einen mitgebrachten `httpx.Client` mit
`follow_redirects=True` ab** (`hgis.UnsafeTransportError`). Der Grund liegt
im Wächter, nicht im Boden: `RequestGuard` prüft jeden Umleitungssprung
selbst, indem er die 3xx-Antwort liest und selbst entscheidet, ob er folgt --
das setzt voraus, dass `httpx` eine Umleitung unverändert zurückgibt, statt
ihr selbst zu folgen. Mit `follow_redirects=True` löst `httpx` die ganze
Kette **innerhalb** des einen Aufrufs auf, den der Wächter schon geprüft hat;
seine Schleife läuft dann kein zweites Mal, und ein durchgelassener
Schreibvorgang -- vollständiger Rumpf, Client-Name-Kopfzeile eingeschlossen
-- verlässt die Bibliothek so ungeprüft an der Stelle, auf die der erste
Sprung zeigt. Übergeben Sie einen Client mit `follow_redirects=False` (die
Vorgabe), oder lassen Sie das Argument weg.

Ein `Transport` bietet zwei Formen an: `request()`, eine Anfrage gegen eine
Antwort, und `events()`, ein Strom. `HttpxTransport.events()` öffnet
`GET /api/events` mit `httpx`s Streaming-Modus und liest Server-Sent Events
zeilenweise; `PyodideTransport.events()` wirft `TransportError` -- eine
synchrone `XMLHttpRequest` kann einen Strom nicht schrittweise lesen, das
braucht eine eigene Anbindung, die diese Stufe noch nicht baut.

## Der Ereigniskanal (Grundlage)

`client.events()` öffnet den Live-Kanal und liefert `hgis.Event` (`name`,
`data`, `id`) für jedes ankommende Ereignis:

```python
for event in client.events():
    print(event.name, event.data)          # z. B. "project-view-state", '{"projectId":...}'
```

Diese Stufe baut nur die Grundlage. Sie liest nicht selbst vom Kanal --
wiederverbinden nach einem Abbruch, das eigene Echo an `origin ==
client.client_id` erkennen: das ist Sache einer späteren Stufe. Geprüft ist
hier, dass ein Ereignis tatsächlich ankommt (`tests/test_events.py`, gegen
einen echten Server auf localhost) und dass der Pfad vom `RequestGuard`
geprüft wird wie jeder andere auch -- mit einer eigenen, wörtlichen Prüfung
auf `/api/events`, weil die allgemeine Erlaubnisliste jedes `GET` ohnehin
durchlässt und daher für einen Strom nichts Eigenes prüfen würde.

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

- Kein Umbenennen eines Felds (`PATCH .../fields/{id}`) -- nur anlegen und
  löschen.
- Kein Teilen, Zusammenführen oder Neuordnen von Layern über diese
  Bibliothek.
- Kein Löschen eines ganzen Projekts.
- Kein Lesen des Papierkorbs oder des Änderungsprotokolls über eine eigene
  Methode -- `client.get(".../trash")` und `client.get(".../changes")`
  funktionieren bereits, denn Lesen ist uneingeschränkt; eine eigene,
  typisierte Oberfläche dafür ist nicht Teil dieser Stufe.
- Der Ereigniskanal ist nur die Grundlage, siehe oben -- kein Wiederverbinden,
  kein Erkennen des eigenen Echos, kein Konsument, der ihn tatsächlich nutzt.
- `PyodideTransport.events()` läuft nicht -- nur unter CPython.
- Kein MCP-Server.
- Kein Editor im Browser.
- `to_dataframe()` überträgt GeoJSON. Arrow und GeoParquet sind eine Frage der
  Geschwindigkeit. Sie kommen später.

Die Bibliothek fragt nie mehr als 1000 Zeilen je Seite an. Das ist die
Obergrenze des Servers. Er lehnt einen größeren Wert ab.
