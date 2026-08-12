package de.kreuter.hgis.catalog;

import de.kreuter.hgis.catalog.dto.StyleDtos;
import de.kreuter.hgis.common.BadRequestException;
import de.kreuter.hgis.common.GeometryType;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Validates a style before it is stored, and works out which attributes the tiles of a
 * styled layer have to carry.
 *
 * <p>Validation is not a convenience here. A style names attributes, and an attribute name
 * ends up as a column in the tile query -- so this class applies the same rule the
 * FilterParser does: every name is resolved through {@code layer_field}, and only the
 * {@code column_name} that comes back may travel further. A name that does not resolve is
 * a 400, never a query.
 *
 * <p>What gets stored is not the document the client sent but the one this class produced
 * from it. Anything the schema does not describe is dropped on the way through, so
 * whatever is later read back out of {@code layer.style} has been through these checks --
 * including after a client, a script or a restore wrote something odd.
 *
 * <p>{@code renderer.field} and {@code labels.field} are canonicalised to the resolved
 * column name. The client may send either spelling, but the tile carries its attributes
 * under the column name -- the only identifier {@code SqlIdentifier} will quote -- and
 * storing that name means the frontend's {@code ["get", field]} matches the tile without
 * a second lookup.
 */
@Service
public class LayerStyleService {

	private static final Logger log = LoggerFactory.getLogger(LayerStyleService.class);

	/** Exactly what the contract allows. No short form, no alpha channel: one spelling, one meaning. */
	private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");

	private static final Set<String> RENDERER_TYPES = Set.of(
			StyleDtos.RENDERER_SINGLE, StyleDtos.RENDERER_CATEGORIZED, StyleDtos.RENDERER_GRADUATED);

	private static final Set<String> SYMBOL_KINDS = Set.of(
			StyleDtos.SYMBOL_MARKER, StyleDtos.SYMBOL_LINE, StyleDtos.SYMBOL_FILL);

	private static final int SUPPORTED_VERSION = 1;

	/** A legend nobody can read is not a style. The caps also keep a jsonb column from becoming a dump. */
	private static final int MAX_CATEGORIES = 500;
	private static final int MAX_CLASSES = 50;
	private static final int MAX_DASH_SEGMENTS = 8;
	private static final int MAX_LABEL_LENGTH = 200;

	private static final int MAX_ZOOM = 24;

	/**
	 * The three "no style set" symbols a renderer reset by {@link #cleanupAfterFieldRemoval}
	 * falls back to as a last resort. Kept byte for byte identical to the frontend's
	 * {@code defaults.ts} -- the monochrome look of a layer nobody has styled yet -- so a
	 * field's removal never dresses the layer up in a colour nobody chose.
	 */
	private static final StyleDtos.Symbol DEFAULT_MARKER = new StyleDtos.Symbol(
			StyleDtos.SYMBOL_MARKER, "circle", 3.0, "#404040", "#fafafa", 1.0,
			null, null, null, null, null, null);
	private static final StyleDtos.Symbol DEFAULT_LINE = new StyleDtos.Symbol(
			StyleDtos.SYMBOL_LINE, null, null, null, null, null,
			"#404040", 1.25, null, null, null, null);
	private static final StyleDtos.Symbol DEFAULT_FILL = new StyleDtos.Symbol(
			StyleDtos.SYMBOL_FILL, null, null, "#404040", null, null,
			null, null, null, 0.25, "#262626", 1.0);

	private final ObjectMapper objectMapper;
	private final LayerFieldRepository fieldRepository;

	LayerStyleService(ObjectMapper objectMapper, LayerFieldRepository fieldRepository) {
		this.objectMapper = objectMapper;
		this.fieldRepository = fieldRepository;
	}

	/**
	 * Checks a style against the layer it belongs to and returns the canonical JSON to
	 * store.
	 *
	 * @param node   the {@code style} member of the request; a JSON null resets the style
	 * @param fields the layer's fields, the only names a style may refer to
	 * @return canonical JSON, or null to clear the style
	 * @throws BadRequestException on anything the schema or the layer does not allow
	 */
	public String validateAndSerialize(JsonNode node, List<LayerField> fields) {
		if (node == null || node.isNull()) {
			return null;
		}
		if (!node.isObject()) {
			throw new BadRequestException("Der Style muss ein JSON-Objekt sein");
		}

		StyleDtos.Style style;
		try {
			style = objectMapper.treeToValue(node, StyleDtos.Style.class);
		}
		catch (JacksonException ex) {
			throw new BadRequestException("Das Programm kann den Style nicht lesen: " + firstLine(ex));
		}

		return objectMapper.writeValueAsString(validate(style, fields));
	}

	/**
	 * The attributes this layer's tiles have to carry, as column names in a stable order.
	 *
	 * <p>Only the fields an active renderer or an enabled label actually classifies by --
	 * everything else a client might want about a feature comes from the feature API, which
	 * has no tile budget to spend. A layer without a style needs no attributes at all and
	 * costs no lookup: the check on the style column comes first.
	 */
	public Set<String> tileColumns(Layer layer) {
		if (layer.getStyle() == null) {
			return Set.of();
		}
		return tileColumns(layer.getStyle(), fieldRepository.findByLayerIdOrderByOrdinalAsc(layer.getId()));
	}

	/**
	 * The same set, for a style that is not (or not yet) the layer's stored one -- which is
	 * how an update decides whether {@code style_version} has to move.
	 */
	public Set<String> tileColumns(String styleJson, List<LayerField> fields) {
		Set<String> columns = new LinkedHashSet<>();
		StyleDtos.Style style = readStored(styleJson);
		if (style == null) {
			return columns;
		}

		StyleDtos.Renderer renderer = style.renderer();
		if (renderer != null && classifies(renderer.type())) {
			// Skipping an unresolvable name rather than throwing: this runs on the tile
			// path, and a style that no longer matches its layer must degrade to the plain
			// tile, not turn every tile request into a 500.
			LayerFields.find(renderer.field(), fields)
					.ifPresent(field -> columns.add(field.getColumnName()));
		}

		StyleDtos.Labels labels = style.labels();
		if (labels != null && labels.isEnabled()) {
			LayerFields.find(labels.field(), fields)
					.ifPresent(field -> columns.add(field.getColumnName()));
		}
		return columns;
	}

	/**
	 * Whether the layer's style currently classifies or labels by one particular column
	 * -- the two flags {@code GET .../fields/{fieldId}/usage} answers (CONTRACT.md phase
	 * 12), so the confirmation dialog can say what deleting the field would actually
	 * touch instead of asking a question nobody can weigh.
	 */
	public FieldUsage fieldUsage(String styleJson, String columnName) {
		StyleDtos.Style style = readStored(styleJson);
		if (style == null) {
			return new FieldUsage(false, false);
		}

		StyleDtos.Renderer renderer = style.renderer();
		boolean usedByRenderer = renderer != null && classifies(renderer.type())
				&& columnName.equals(renderer.field());

		StyleDtos.Labels labels = style.labels();
		boolean usedByLabels = labels != null && labels.isEnabled() && columnName.equals(labels.field());

		return new FieldUsage(usedByRenderer, usedByLabels);
	}

	/**
	 * @param usedByRenderer the renderer classifies (categorized or graduated) by this field
	 * @param usedByLabels   the labels are switched on and read this field
	 */
	public record FieldUsage(boolean usedByRenderer, boolean usedByLabels) {
	}

	/**
	 * Rewrites a stored style so it no longer refers to a field that is about to be
	 * dropped from the layer (CONTRACT.md phase 12) -- the dead end the contract calls
	 * out by name: without this, a style pointing at a column that no longer exists
	 * would fail {@link #validateAndSerialize} forever after, freezing the layer's
	 * symbology on every future save, even one that touches nothing about the style.
	 *
	 * <p>A renderer classifying by the removed field falls back to a plain single
	 * symbol, the same conversion the symbology panel itself offers on the way from a
	 * classification back to "Einzelsymbol" ({@code renderer.ts#convertRenderer}):
	 * whichever symbol the renderer already carried -- its own for a single renderer,
	 * the fallback symbol for a categorized or graduated one -- survives; only the
	 * classification is lost, because the field it depended on is gone. If neither
	 * symbol was ever set, one of the three monochrome defaults above stands in, so the
	 * result is always a renderer this class can store. An enabled labels block reading
	 * the removed field is switched off outright rather than pointed at some other
	 * field -- guessing a replacement would label some features with an attribute the
	 * user never chose to show.
	 *
	 * <p>Whatever comes out of that is run back through {@link #validate} against the
	 * layer's remaining fields, exactly like a client-supplied style would be. That is
	 * the actual guarantee this method gives: what {@link LayerFieldService#deleteField}
	 * ends up storing is, by construction, something this class's own validation
	 * accepts.
	 *
	 * @param styleJson       the layer's currently stored style, or null
	 * @param removedColumn   column name of the field the caller is about to drop
	 * @param remainingFields every field of the layer except the one being removed
	 * @param geometryType    the layer's geometry type, for the rare case a renderer has
	 *                        to fall back to a symbol it never had one of its own
	 * @return canonical JSON to store; the original {@code styleJson}, unchanged, if the
	 *         removed field was not referenced by the style at all
	 */
	public String cleanupAfterFieldRemoval(String styleJson, String removedColumn,
			List<LayerField> remainingFields, GeometryType geometryType) {
		StyleDtos.Style style = readStored(styleJson);
		if (style == null) {
			return styleJson;
		}

		StyleDtos.Renderer renderer = style.renderer();
		boolean rendererHit = renderer != null && removedColumn.equals(renderer.field());

		StyleDtos.Labels labels = style.labels();
		boolean labelsHit = labels != null && removedColumn.equals(labels.field());

		if (!rendererHit && !labelsHit) {
			return styleJson;
		}

		if (rendererHit) {
			StyleDtos.Symbol symbol = renderer.symbol() != null ? renderer.symbol()
					: renderer.fallbackSymbol() != null ? renderer.fallbackSymbol()
					: defaultSymbolFor(geometryType);
			renderer = new StyleDtos.Renderer(StyleDtos.RENDERER_SINGLE, symbol, null, null, null, null);
		}
		if (labelsHit) {
			labels = new StyleDtos.Labels(false, null, labels.size(), labels.color(), labels.haloColor(),
					labels.haloWidth(), labels.minZoom(), labels.allowOverlap());
		}

		StyleDtos.Style cleaned = new StyleDtos.Style(
				style.version(), renderer, labels, style.opacity(), style.minZoom(), style.maxZoom());
		return objectMapper.writeValueAsString(validate(cleaned, remainingFields));
	}

	private static StyleDtos.Symbol defaultSymbolFor(GeometryType geometryType) {
		return switch (geometryType) {
			case MULTIPOINT -> DEFAULT_MARKER;
			case MULTILINESTRING -> DEFAULT_LINE;
			case MULTIPOLYGON, GEOMETRY -> DEFAULT_FILL;
		};
	}

	// --- validation -------------------------------------------------------------------

	private StyleDtos.Style validate(StyleDtos.Style style, List<LayerField> fields) {
		if (style.version() != null && style.version() != SUPPORTED_VERSION) {
			throw new BadRequestException("Unbekannte Style-Version: " + style.version()
					+ ". Der Server unterstützt nur Version " + SUPPORTED_VERSION + ".");
		}
		if (style.renderer() == null) {
			throw new BadRequestException("Der Style braucht einen renderer");
		}
		requireRange(style.opacity(), 0, 1, "opacity");
		requireZoomRange(style.minZoom(), style.maxZoom());

		return new StyleDtos.Style(
				SUPPORTED_VERSION,
				validateRenderer(style.renderer(), fields),
				validateLabels(style.labels(), fields),
				style.opacity(),
				style.minZoom(),
				style.maxZoom());
	}

	private StyleDtos.Renderer validateRenderer(StyleDtos.Renderer renderer, List<LayerField> fields) {
		String type = renderer.type();
		if (type == null || !RENDERER_TYPES.contains(type)) {
			throw new BadRequestException("Unbekannter Renderer-Typ: " + type
					+ ". Erlaubt sind " + String.join(", ", RENDERER_TYPES.stream().sorted().toList()) + ".");
		}

		if (type.equals(StyleDtos.RENDERER_SINGLE) && renderer.symbol() == null) {
			throw new BadRequestException("Ein Einzelsymbol-Renderer braucht ein symbol");
		}

		// The field is validated whenever it is present, not only when the current type
		// uses it: a UI that keeps the previous classification around while the user tries
		// out "Einzelsymbol" should not be able to park an unknown name in the document.
		String field = null;
		if (renderer.field() != null && !renderer.field().isBlank()) {
			LayerField resolved = LayerFields.require(renderer.field(), fields);
			if (type.equals(StyleDtos.RENDERER_GRADUATED) && !LayerFields.isNumeric(resolved)) {
				throw new BadRequestException("Feld " + resolved.getSourceName() + " ist vom Typ "
						+ resolved.getDataType() + ". Klasseneinteilung ist für diesen Feldtyp nicht möglich.");
			}
			field = resolved.getColumnName();
		}
		else if (classifies(type)) {
			throw new BadRequestException("Der Renderer-Typ " + type + " braucht ein Feld");
		}

		return new StyleDtos.Renderer(
				type,
				validateSymbol(renderer.symbol(), "symbol"),
				field,
				validateCategories(renderer.categories()),
				validateClasses(renderer.classes()),
				validateSymbol(renderer.fallbackSymbol(), "fallbackSymbol"));
	}

	private List<StyleDtos.Category> validateCategories(List<StyleDtos.Category> categories) {
		if (categories == null) {
			return null;
		}
		requireAtMost(categories.size(), MAX_CATEGORIES, "Kategorien");
		return categories.stream().map(category -> {
			requireScalar(category.value());
			requireLabel(category.label());
			return new StyleDtos.Category(category.value(), category.label(),
					validateSymbol(category.symbol(), "symbol einer Kategorie"));
		}).toList();
	}

	private List<StyleDtos.ClassBreak> validateClasses(List<StyleDtos.ClassBreak> classes) {
		if (classes == null) {
			return null;
		}
		requireAtMost(classes.size(), MAX_CLASSES, "Klassen");
		return classes.stream().map(range -> {
			if (range.min() == null || range.max() == null) {
				throw new BadRequestException("Jede Klasse braucht min und max");
			}
			if (range.min() > range.max()) {
				throw new BadRequestException(
						"Klassengrenze min (" + range.min() + ") liegt über max (" + range.max() + ")");
			}
			requireFinite(range.min(), "min einer Klasse");
			requireFinite(range.max(), "max einer Klasse");
			requireLabel(range.label());
			return new StyleDtos.ClassBreak(range.min(), range.max(), range.label(),
					validateSymbol(range.symbol(), "symbol einer Klasse"));
		}).toList();
	}

	private StyleDtos.Labels validateLabels(StyleDtos.Labels labels, List<LayerField> fields) {
		if (labels == null) {
			return null;
		}

		String field = null;
		if (labels.field() != null && !labels.field().isBlank()) {
			field = LayerFields.require(labels.field(), fields).getColumnName();
		}
		else if (labels.isEnabled()) {
			throw new BadRequestException("Eine eingeschaltete Beschriftung braucht ein Feld");
		}

		requireNonNegative(labels.size(), "labels.size");
		requireNonNegative(labels.haloWidth(), "labels.haloWidth");
		requireColor(labels.color(), "labels.color");
		requireColor(labels.haloColor(), "labels.haloColor");
		requireZoom(labels.minZoom(), "labels.minZoom");

		return new StyleDtos.Labels(labels.enabled(), field, labels.size(), labels.color(),
				labels.haloColor(), labels.haloWidth(), labels.minZoom(), labels.allowOverlap());
	}

	private StyleDtos.Symbol validateSymbol(StyleDtos.Symbol symbol, String what) {
		if (symbol == null) {
			return null;
		}
		if (symbol.kind() == null || !SYMBOL_KINDS.contains(symbol.kind())) {
			throw new BadRequestException("Unbekannte Symbolart in " + what + ": " + symbol.kind()
					+ ". Erlaubt sind " + String.join(", ", SYMBOL_KINDS.stream().sorted().toList()) + ".");
		}

		requireColor(symbol.fillColor(), what + ".fillColor");
		requireColor(symbol.strokeColor(), what + ".strokeColor");
		requireColor(symbol.color(), what + ".color");
		requireColor(symbol.outlineColor(), what + ".outlineColor");

		requireNonNegative(symbol.size(), what + ".size");
		requireNonNegative(symbol.strokeWidth(), what + ".strokeWidth");
		requireNonNegative(symbol.width(), what + ".width");
		requireNonNegative(symbol.outlineWidth(), what + ".outlineWidth");
		requireRange(symbol.fillOpacity(), 0, 1, what + ".fillOpacity");

		if (symbol.dashArray() != null) {
			requireAtMost(symbol.dashArray().size(), MAX_DASH_SEGMENTS, "Strichsegmente");
			symbol.dashArray().forEach(segment -> requireNonNegative(segment, what + ".dashArray"));
		}

		// shape is deliberately not checked against a list: the contract says unknown
		// shapes render as circles until sprites exist, so rejecting them now would break
		// documents that are meant to survive that change.
		return symbol;
	}

	// --- small checks -----------------------------------------------------------------

	private static boolean classifies(String rendererType) {
		return StyleDtos.RENDERER_CATEGORIZED.equals(rendererType)
				|| StyleDtos.RENDERER_GRADUATED.equals(rendererType);
	}

	private static void requireColor(String value, String what) {
		if (value != null && !HEX_COLOR.matcher(value).matches()) {
			throw new BadRequestException(
					what + " ist keine Farbe der Form #rrggbb: " + value);
		}
	}

	private static void requireRange(Double value, double min, double max, String what) {
		if (value == null) {
			return;
		}
		requireFinite(value, what);
		if (value < min || value > max) {
			throw new BadRequestException(what + " muss zwischen " + min + " und " + max
					+ " liegen. Wert war " + value + ".");
		}
	}

	private static void requireNonNegative(Double value, String what) {
		if (value == null) {
			return;
		}
		requireFinite(value, what);
		if (value < 0) {
			throw new BadRequestException(what + " darf nicht negativ sein. Wert war " + value + ".");
		}
	}

	/** NaN and infinity survive JSON via non-standard literals and would poison every comparison. */
	private static void requireFinite(Double value, String what) {
		if (value != null && !Double.isFinite(value)) {
			throw new BadRequestException(what + " ist keine gültige Zahl");
		}
	}

	private static void requireZoom(Integer value, String what) {
		if (value != null && (value < 0 || value > MAX_ZOOM)) {
			throw new BadRequestException(what + " muss zwischen 0 und " + MAX_ZOOM
					+ " liegen. Wert war " + value + ".");
		}
	}

	private static void requireZoomRange(Integer minZoom, Integer maxZoom) {
		requireZoom(minZoom, "minZoom");
		requireZoom(maxZoom, "maxZoom");
		if (minZoom != null && maxZoom != null && minZoom > maxZoom) {
			throw new BadRequestException("minZoom darf maxZoom nicht überschreiten");
		}
	}

	private static void requireScalar(Object value) {
		if (value == null || value instanceof String || value instanceof Number
				|| value instanceof Boolean) {
			return;
		}
		throw new BadRequestException(
				"Der Wert einer Kategorie muss ein Text, eine Zahl oder ein Wahrheitswert sein");
	}

	private static void requireLabel(String label) {
		if (label != null && label.length() > MAX_LABEL_LENGTH) {
			throw new BadRequestException(
					"Beschriftungen in der Legende dürfen höchstens " + MAX_LABEL_LENGTH
							+ " Zeichen lang sein");
		}
	}

	private static void requireAtMost(int actual, int limit, String what) {
		if (actual > limit) {
			throw new BadRequestException("Höchstens " + limit + " " + what
					+ " sind erlaubt. Angegeben waren " + actual + ".");
		}
	}

	// --- reading back -----------------------------------------------------------------

	/**
	 * Reads a stored style. Only ever sees documents this class wrote, so a failure means
	 * the column was written past it -- worth a log line, not worth failing the request
	 * that happened to read it.
	 */
	private StyleDtos.Style readStored(String styleJson) {
		if (styleJson == null || styleJson.isBlank()) {
			return null;
		}
		try {
			return objectMapper.readValue(styleJson, StyleDtos.Style.class);
		}
		catch (JacksonException ex) {
			log.warn("Stored style could not be read, treating the layer as unstyled", ex);
			return null;
		}
	}

	/** Jackson appends the parse position over several lines; the first one carries the reason. */
	private static String firstLine(JacksonException ex) {
		String message = ex.getOriginalMessage();
		if (message == null || message.isBlank()) {
			return "unlesbarer Inhalt";
		}
		int newline = message.indexOf('\n');
		return newline < 0 ? message : message.substring(0, newline);
	}
}
