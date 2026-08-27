"""
The semantic style schema stored in ``layer.style`` -- read with
:attr:`hgis.layer.Layer.style`, written with :meth:`hgis.layer.Layer.set_style`.

Deliberately not the MapLibre style specification -- see ``StyleDtos`` on the
server, which this module mirrors on the Python side. What travels here says
what the user meant ("colour by this attribute", "one dot per point within
30 pixels of each other"), and the frontend alone turns that into paint
expressions. Reusing the server's own words:

    >>> layer.set_style(hgis.Style(hgis.Renderer("heatmap", field="laut_wert", ramp="inferno")))

A missing style (``None``) is the default monochrome rendering, not an error.

Every dataclass here is a partial shape, the same way the server's records
are: which members carry meaning depends on :attr:`Renderer.type` and
:attr:`Symbol.kind`. This module builds and reads the JSON; it does not
repeat the server's validation -- colour format, numeric ranges, whether a
field name really belongs to the layer are all checked once, on the server,
and its answer names what would have been valid. Two members are the
exception: :attr:`Renderer.type` and :attr:`Style.version` are each a small,
fixed set the server checks by simple equality, not a range or a format --
so a typo or a stale version is caught before the request leaves, see
:func:`to_style_json`, because the alternative is an HTTP 400 that repeats
the same few names in the server's own words.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from .errors import InvalidArgumentError

#: The only schema version this library and the server understand today --
#: see SUPPORTED_VERSION in the server's LayerStyleService.
SUPPORTED_VERSION = 1

#: The four renderers ``layer.style.renderer.type`` accepts.
RENDERER_SINGLE = "single"
RENDERER_CATEGORIZED = "categorized"
RENDERER_GRADUATED = "graduated"
RENDERER_HEATMAP = "heatmap"

_RENDERER_TYPES = frozenset(
    {RENDERER_SINGLE, RENDERER_CATEGORIZED, RENDERER_GRADUATED, RENDERER_HEATMAP}
)

#: The three symbol kinds -- see :class:`Symbol`.
SYMBOL_MARKER = "marker"
SYMBOL_LINE = "line"
SYMBOL_FILL = "fill"


@dataclass(frozen=True)
class Symbol:
    """
    One symbol for any geometry type. ``kind`` says which of the other
    members apply: marker uses shape/size/fill_color/stroke_color/
    stroke_width, line uses color/width/dash_array, fill uses
    fill_color/fill_opacity/outline_color/outline_width.

    :param kind: :data:`SYMBOL_MARKER`, :data:`SYMBOL_LINE` or :data:`SYMBOL_FILL`
    :param shape: only ``"circle"`` renders as such today; other values are
        accepted and drawn as circles, see the server's own docstring
    """

    kind: str
    shape: str | None = None
    size: float | None = None
    fill_color: str | None = None
    stroke_color: str | None = None
    stroke_width: float | None = None
    color: str | None = None
    width: float | None = None
    dash_array: list[float] | None = None
    fill_opacity: float | None = None
    outline_color: str | None = None
    outline_width: float | None = None

    def to_json(self) -> dict[str, Any]:
        return _drop_none(
            {
                "kind": self.kind,
                "shape": self.shape,
                "size": self.size,
                "fillColor": self.fill_color,
                "strokeColor": self.stroke_color,
                "strokeWidth": self.stroke_width,
                "color": self.color,
                "width": self.width,
                "dashArray": self.dash_array,
                "fillOpacity": self.fill_opacity,
                "outlineColor": self.outline_color,
                "outlineWidth": self.outline_width,
            }
        )

    @staticmethod
    def from_json(data: dict[str, Any] | None) -> "Symbol | None":
        if data is None:
            return None
        return Symbol(
            kind=data.get("kind"),
            shape=data.get("shape"),
            size=data.get("size"),
            fill_color=data.get("fillColor"),
            stroke_color=data.get("strokeColor"),
            stroke_width=data.get("strokeWidth"),
            color=data.get("color"),
            width=data.get("width"),
            dash_array=data.get("dashArray"),
            fill_opacity=data.get("fillOpacity"),
            outline_color=data.get("outlineColor"),
            outline_width=data.get("outlineWidth"),
        )


@dataclass(frozen=True)
class Category:
    """
    One entry of a categorized renderer: one attribute value, one symbol.

    :param value: the attribute value this entry matches -- a scalar, or
        None for the features that have no value at all. Written out even
        when None: "objects without a use type" is a category a user can
        legitimately colour, and it has to survive the round trip through
        JSON as that, not as an absent member.
    """

    value: Any
    label: str | None = None
    symbol: Symbol | None = None

    def to_json(self) -> dict[str, Any]:
        body: dict[str, Any] = {"value": self.value}
        if self.label is not None:
            body["label"] = self.label
        if self.symbol is not None:
            body["symbol"] = self.symbol.to_json()
        return body

    @staticmethod
    def from_json(data: dict[str, Any]) -> "Category":
        return Category(
            value=data.get("value"),
            label=data.get("label"),
            symbol=Symbol.from_json(data.get("symbol")),
        )


@dataclass(frozen=True)
class ClassBreak:
    """
    One range of a graduated renderer.

    Half-open in intent: minimum inclusive, maximum exclusive except for the
    last class.
    """

    minimum: float
    maximum: float
    label: str | None = None
    symbol: Symbol | None = None

    def to_json(self) -> dict[str, Any]:
        return _drop_none(
            {
                "min": self.minimum,
                "max": self.maximum,
                "label": self.label,
                "symbol": self.symbol.to_json() if self.symbol is not None else None,
            }
        )

    @staticmethod
    def from_json(data: dict[str, Any]) -> "ClassBreak":
        return ClassBreak(
            minimum=data.get("min"),
            maximum=data.get("max"),
            label=data.get("label"),
            symbol=Symbol.from_json(data.get("symbol")),
        )


@dataclass(frozen=True)
class Labels:
    """
    :param field: the attribute to write next to the geometry. Required
        once ``enabled`` is True -- the server refuses an enabled label
        block with no field, naming that
    :param min_zoom: labels usually only make sense once the map is close
        enough
    """

    enabled: bool = False
    field: str | None = None
    size: float | None = None
    color: str | None = None
    halo_color: str | None = None
    halo_width: float | None = None
    min_zoom: int | None = None
    allow_overlap: bool | None = None

    def to_json(self) -> dict[str, Any]:
        return _drop_none(
            {
                "enabled": self.enabled,
                "field": self.field,
                "size": self.size,
                "color": self.color,
                "haloColor": self.halo_color,
                "haloWidth": self.halo_width,
                "minZoom": self.min_zoom,
                "allowOverlap": self.allow_overlap,
            }
        )

    @staticmethod
    def from_json(data: dict[str, Any] | None) -> "Labels | None":
        if data is None:
            return None
        return Labels(
            enabled=bool(data.get("enabled")),
            field=data.get("field"),
            size=data.get("size"),
            color=data.get("color"),
            halo_color=data.get("haloColor"),
            halo_width=data.get("haloWidth"),
            min_zoom=data.get("minZoom"),
            allow_overlap=data.get("allowOverlap"),
        )


@dataclass(frozen=True)
class Renderer:
    """
    How a layer's features get their colour.

    :param type: :data:`RENDERER_SINGLE`, :data:`RENDERER_CATEGORIZED`,
        :data:`RENDERER_GRADUATED` or :data:`RENDERER_HEATMAP`
    :param symbol: the one symbol, for :data:`RENDERER_SINGLE`
    :param field: classification attribute, for categorized, graduated and
        heatmap. Optional for heatmap -- without it, every point counts
        equally. By source name or column name; the server resolves and
        canonicalises it to the column name, the same as a filter would
    :param categories: value to symbol, for categorized
    :param classes: numeric ranges to symbol, for graduated
    :param fallback_symbol: used for everything no category or class covers.
        Required for categorized and graduated -- the server refuses either one
        without it with an HTTP 400
    :param method: graduated only -- which of ``/classify``'s methods
        computed ``classes``: quantile, equalInterval or naturalBreaks
    :param class_count: graduated only -- how many classes ``method`` was
        asked to produce
    :param ramp: graduated and heatmap: the colour ramp's display name,
        e.g. ``"viridis"`` or ``"inferno"``. The server checks it against its
        own catalogue and refuses an unknown name with an HTTP 400 that names
        the valid ones; the frontend alone turns a valid name into actual
        colours. This library keeps no copy of that catalogue -- on purpose,
        the same reasoning as everything else in :func:`to_style_json`'s
        docstring: a second list here would drift from the server's, and
        would force a release of this library before a new ramp could be
        used at all
    :param palette: categorized only: the colour palette's display name
    :param radius: heatmap only -- influence radius in screen pixels, 1..100
    :param intensity: heatmap only -- a multiplier, 0.1..5.0
    :param weight_min: heatmap only -- the field value mapped to weight 0. Optional;
        absent together with ``weight_max`` means the automatic window (0, or the data
        minimum once negative values occur, up to the field's maximum). Either both are
        given or neither is -- the server checks that, see the module docstring
    :param weight_max: heatmap only -- the field value mapped to weight 1, checked by the
        server to be strictly greater than ``weight_min``
    """

    type: str
    symbol: Symbol | None = None
    field: str | None = None
    categories: list[Category] | None = None
    classes: list[ClassBreak] | None = None
    fallback_symbol: Symbol | None = None
    method: str | None = None
    class_count: int | None = None
    ramp: str | None = None
    palette: str | None = None
    radius: float | None = None
    intensity: float | None = None
    weight_min: float | None = None
    weight_max: float | None = None

    def to_json(self) -> dict[str, Any]:
        return _drop_none(
            {
                "type": self.type,
                "symbol": self.symbol.to_json() if self.symbol is not None else None,
                "field": self.field,
                "categories": (
                    [item.to_json() for item in self.categories]
                    if self.categories is not None
                    else None
                ),
                "classes": (
                    [item.to_json() for item in self.classes] if self.classes is not None else None
                ),
                "fallbackSymbol": (
                    self.fallback_symbol.to_json() if self.fallback_symbol is not None else None
                ),
                "method": self.method,
                "classCount": self.class_count,
                "ramp": self.ramp,
                "palette": self.palette,
                "radius": self.radius,
                "intensity": self.intensity,
                "weightMin": self.weight_min,
                "weightMax": self.weight_max,
            }
        )

    @staticmethod
    def from_json(data: dict[str, Any]) -> "Renderer":
        categories = data.get("categories")
        classes = data.get("classes")
        return Renderer(
            type=data.get("type"),
            symbol=Symbol.from_json(data.get("symbol")),
            field=data.get("field"),
            categories=([Category.from_json(item) for item in categories] if categories else None),
            classes=([ClassBreak.from_json(item) for item in classes] if classes else None),
            fallback_symbol=Symbol.from_json(data.get("fallbackSymbol")),
            method=data.get("method"),
            class_count=data.get("classCount"),
            ramp=data.get("ramp"),
            palette=data.get("palette"),
            radius=data.get("radius"),
            intensity=data.get("intensity"),
            weight_min=data.get("weightMin"),
            weight_max=data.get("weightMax"),
        )


@dataclass(frozen=True)
class Style:
    """
    A whole style, as stored in ``layer.style``.

    :param renderer: how features get their colour, see :class:`Renderer`
    :param labels: the optional label layer
    :param opacity: 0..1, applied to fill, line and marker alike
    :param min_zoom: style-level zoom window, independent of the layer's own one
    :param version: schema version; :data:`SUPPORTED_VERSION` is the only one
        that exists, and the only one :func:`to_style_json` lets through
    """

    renderer: Renderer
    labels: Labels | None = None
    opacity: float | None = None
    min_zoom: int | None = None
    max_zoom: int | None = None
    version: int = SUPPORTED_VERSION

    def to_json(self) -> dict[str, Any]:
        return _drop_none(
            {
                "version": self.version,
                "renderer": self.renderer.to_json(),
                "labels": self.labels.to_json() if self.labels is not None else None,
                "opacity": self.opacity,
                "minZoom": self.min_zoom,
                "maxZoom": self.max_zoom,
            }
        )

    @staticmethod
    def from_json(data: dict[str, Any] | None) -> "Style | None":
        if data is None:
            return None
        renderer = data.get("renderer")
        return Style(
            renderer=Renderer.from_json(renderer) if renderer is not None else None,
            labels=Labels.from_json(data.get("labels")),
            opacity=data.get("opacity"),
            min_zoom=data.get("minZoom"),
            max_zoom=data.get("maxZoom"),
            version=data.get("version", SUPPORTED_VERSION),
        )


def to_style_json(style: "Style | dict[str, Any] | None") -> dict[str, Any] | None:
    """
    Normalise a :class:`Style`, or a plain ``dict`` shaped like one, into the
    JSON body :meth:`hgis.layer.Layer.set_style` sends -- and catch the one
    mistake worth catching before the request leaves.

    Everything the schema itself does not settle -- colour format, numeric
    ranges, whether a field really belongs to the layer -- is the server's
    job, and its answer already names what would have been valid; repeating
    that here would only add a second place to keep in step with the first.
    ``version`` and ``renderer.type`` are different: each is a small, fixed
    set the server checks by simple equality rather than by a range or a
    format, so a stale version or a typo in the renderer name reaches the
    server as a plain unrecognised value and comes back as an HTTP 400 that
    says, in the server's own words, what this function can say just as well
    before anything was sent.

    :raises hgis.errors.InvalidArgumentError: ``style`` is neither a
        :class:`Style` nor a ``dict``; its ``version`` is present and not
        :data:`SUPPORTED_VERSION`; or its ``renderer.type`` is not one of the
        four that exist -- naming what was found and what is allowed
    """
    if style is None:
        return None

    body = style.to_json() if isinstance(style, Style) else style
    if not isinstance(body, dict):
        raise InvalidArgumentError(
            f"Ein Style muss ein hgis.Style oder ein dict sein, nicht {type(style).__name__}."
        )

    version = body.get("version")
    if version is not None and version != SUPPORTED_VERSION:
        raise InvalidArgumentError(
            f"Unbekannte Style-Version: {version!r}. Der Server unterstützt nur "
            f"Version {SUPPORTED_VERSION}."
        )

    renderer = body.get("renderer")
    renderer_type = renderer.get("type") if isinstance(renderer, dict) else None
    if renderer_type not in _RENDERER_TYPES:
        raise InvalidArgumentError(
            f"Unbekannter Renderer-Typ: {renderer_type!r}. Erlaubt sind "
            f"{', '.join(sorted(_RENDERER_TYPES))}."
        )

    return body


def _drop_none(mapping: dict[str, Any]) -> dict[str, Any]:
    return {key: value for key, value in mapping.items() if value is not None}
