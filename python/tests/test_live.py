"""
The one test that needs a running server.

Everything else runs against stored responses, which proves the library reads
them correctly and proves nothing about whether the server still sends that
shape. This one closes that gap: it checks the paths, the parameter names and
the response fields against the real thing.

It skips itself when no server answers, so a checkout without a backend still
runs green.

    HGIS_URL=http://localhost:8080 python -m pytest        # with a server
    python -m pytest -m "not live"                         # without

Read-only. It sends nothing but GET.
"""

from __future__ import annotations

import os

import pytest

import hgis

URL = os.environ.get("HGIS_URL", hgis.DEFAULT_BASE_URL)

pytestmark = pytest.mark.live


#: A layer worth testing against holds more than one page, so paging is
#: actually exercised, and fewer than the 100.000 ids the fids endpoint will
#: return, so asking for all of them is not an error by itself.
_ENOUGH_TO_PAGE = 1_000
_FID_CEILING = 100_000


def _test_subject(projects):
    """
    Pick a layer that exercises something.

    The smallest layer on a server is typically a drawing with one shape and
    no attributes, which would let this test pass without touching a filter or
    a second page. So: prefer the smallest layer that still needs paging, and
    fall back to the largest one there is.
    """
    candidates = []
    for project in projects:
        if project.feature_count == 0:
            continue
        for layer in project.layers():
            if layer.kind == "VECTOR" and layer.feature_count > 0:
                candidates.append((layer, project))

    pageable = [
        pair
        for pair in candidates
        if _ENOUGH_TO_PAGE < pair[0].feature_count <= _FID_CEILING
    ]
    if pageable:
        # Smallest of the ones that page: enough to be a real test, quick.
        ranked = sorted(pageable, key=lambda pair: pair[0].feature_count)
    else:
        # Nothing pages on this server, so take the largest there is.
        ranked = sorted(candidates, key=lambda pair: pair[0].feature_count, reverse=True)

    for layer, project in ranked:
        if layer.fields():  # a layer without attributes tests no filtering
            return layer, project
    return None, None


@pytest.fixture(scope="module")
def live() -> hgis.Client:
    """A client, or a skip when nothing is listening."""
    client = hgis.connect(URL, timeout=5)
    try:
        client.projects()
    except hgis.HgisError as error:
        pytest.skip(f"Kein hGIS unter {URL}: {error}")
    return client


def test_the_library_matches_the_running_server(live: hgis.Client) -> None:
    """
    Walk the read paths against a real backend and check they agree.

    What this catches that the stored responses cannot: a renamed parameter, a
    moved path, a field that stopped travelling. The stored responses would
    keep passing through all of it.
    """
    projects = live.projects()
    assert projects, "Der Server hat keine Projekte; der Test braucht mindestens eines."

    layer, project = _test_subject(projects)
    if layer is None:
        pytest.skip("Kein Vektor-Layer mit Objekten und Feldern auf diesem Server.")

    # --- the layer answers what describe() needs --------------------------
    description = layer.describe()
    assert description.name == layer.name
    assert description.fields, "Ein Layer ohne Felder taugt nicht als Prüfstück."
    assert description.feature_count == layer.count()

    # --- filtering happens on the server ----------------------------------
    first = description.fields[0]
    restricted = layer.where(f'"{first.name}" IS NOT NULL')
    filled = restricted.count()
    assert 0 <= filled <= description.feature_count

    # The count and the fid list are two different endpoints answering the
    # same question. They have to agree, or one of them is being built wrongly.
    if filled <= _FID_CEILING:
        assert len(restricted.fids()) == filled

    # --- paging reaches the end -------------------------------------------
    # The layer was chosen to need more than one page, so this walks the
    # cursor rather than reading a single response.
    if description.feature_count <= _FID_CEILING:
        assert len(layer.fids()) == description.feature_count
    assert sum(1 for _ in layer.query()) == description.feature_count

    # --- a rectangle over the layer's own extent keeps everything ---------
    if layer.extent:
        min_lng, min_lat, max_lng, max_lat = layer.extent
        pad = 0.001
        covering = layer.bbox(min_lng - pad, min_lat - pad, max_lng + pad, max_lat + pad)
        assert covering.count() == description.feature_count

    # --- the server still refuses an unknown field, and still says which ---
    with pytest.raises(hgis.ApiError) as error:
        layer.where("dieses_feld_gibt_es_nicht = 1").count()
    message = str(error.value)
    assert "Unbekanntes Feld" in message
    assert first.name in message, "Die Fehlermeldung nennt die vorhandenen Felder nicht mehr."

    # --- reading the view state works and stays read-only -----------------
    view = project.view()
    assert view.basemap
    project.selection()  # no assertion: whatever the user last clicked is valid
