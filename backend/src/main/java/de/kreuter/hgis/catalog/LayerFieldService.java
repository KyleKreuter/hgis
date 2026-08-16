package de.kreuter.hgis.catalog;

import de.kreuter.hgis.catalog.dto.LayerDtos;
import de.kreuter.hgis.changelog.ChangeLogAction;
import de.kreuter.hgis.changelog.ChangeLogService;
import de.kreuter.hgis.common.FieldType;
import de.kreuter.hgis.common.FieldValidationException;
import de.kreuter.hgis.common.GeometryType;
import de.kreuter.hgis.common.NotFoundException;
import de.kreuter.hgis.common.SqlIdentifier;
import de.kreuter.hgis.common.TableCreator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adds, renames and deletes attribute fields of an existing layer -- the schema changes
 * CONTRACT.md phases 11 and 12 allow once a layer already has a physical table.
 * Changing a field's type or reordering it stay out of scope: nothing here ever alters
 * a column {@link TableCreator} already created, it only ever widens, narrows,
 * relabels or removes an entry in the catalog.
 *
 * <p>Adding and renaming bump neither {@code data_version} nor {@code style_version}: a
 * new column carries no data yet -- the frontend invalidates its own feature cache
 * after adding one -- and a rename only ever changes {@code source_name}, which
 * neither tiles nor a stored style (both keyed by {@code column_name}) ever see.
 *
 * <p>Deleting one is different: the column genuinely disappears, and if the layer's
 * style classified or labelled by it, that style is rewritten in the same transaction
 * and {@code style_version} moves along with it -- see {@link #deleteField} and
 * {@link LayerStyleService#cleanupAfterFieldRemoval}. {@code data_version} still never
 * moves; a tile's content is unaffected by a column that never travelled inside one
 * (see {@link LayerStyleService#tileColumns}).
 */
@Service
public class LayerFieldService {

	/** Same ceiling as {@link LayerService#create}: a schema, not a dump, however it grew. */
	private static final int MAX_FIELDS = 50;

	private final LayerRepository layerRepository;
	private final LayerFieldRepository fieldRepository;
	private final TableCreator tableCreator;
	private final LayerStyleService styleService;
	private final ChangeLogService changeLog;
	private final JdbcClient jdbc;

	LayerFieldService(LayerRepository layerRepository, LayerFieldRepository fieldRepository,
			TableCreator tableCreator, LayerStyleService styleService, ChangeLogService changeLog,
			JdbcClient jdbc) {
		this.layerRepository = layerRepository;
		this.fieldRepository = fieldRepository;
		this.tableCreator = tableCreator;
		this.styleService = styleService;
		this.changeLog = changeLog;
		this.jdbc = jdbc;
	}

	/**
	 * Widens a layer's payload table by one column and records it in the catalog.
	 * Existing objects read back {@code NULL} for it -- not a migration concern, just
	 * what a freshly added column always contains.
	 */
	@Transactional
	public LayerDtos.Field addField(UUID layerId, LayerDtos.AddFieldRequest request, String clientName) {
		Layer layer = requireLayer(layerId);
		List<LayerField> existing = fieldRepository.findByLayerIdOrderByOrdinalAsc(layerId);
		if (existing.size() >= MAX_FIELDS) {
			throw new FieldValidationException("name",
					"Der Layer hat bereits " + MAX_FIELDS + " Felder. Mehr Felder sind nicht erlaubt.");
		}

		String name = request.name().trim();
		FieldType type = parseFieldType(request.type());
		String columnName = resolveNewColumnName(name, existing);

		tableCreator.addColumn(layer.getTableName(), columnName, type.pgType());

		int ordinal = fieldRepository.maxOrdinal(layerId) + 1;
		LayerField field = fieldRepository.save(
				new LayerField(layer, name, columnName, type.pgType(), ordinal));

		changeLog.record(layer.getProject().getId(), layer.getId(), layer.getName(),
				ChangeLogAction.FIELD_CREATE, clientName, 1, null);
		return toDto(field);
	}

	/**
	 * Changes only {@code source_name}. {@code column_name} and {@code data_type} are the
	 * physical column and stay exactly as they are (CONTRACT.md phase 11, trap 3).
	 */
	@Transactional
	public LayerDtos.Field renameField(UUID layerId, UUID fieldId, LayerDtos.RenameFieldRequest request) {
		requireLayer(layerId);
		List<LayerField> fields = fieldRepository.findByLayerIdOrderByOrdinalAsc(layerId);
		LayerField field = fields.stream()
				.filter(candidate -> candidate.getId().equals(fieldId))
				.findFirst()
				.orElseThrow(() -> new NotFoundException("Feld " + fieldId + " existiert nicht"));

		String name = request.name().trim();
		String wanted = name.toLowerCase(Locale.ROOT);
		for (LayerField other : fields) {
			if (other.getId().equals(field.getId())) {
				// Renaming to the field's own current name -- source or column spelling --
				// is a no-op, not a collision with itself.
				continue;
			}
			if (other.getSourceName().toLowerCase(Locale.ROOT).equals(wanted)
					|| other.getColumnName().toLowerCase(Locale.ROOT).equals(wanted)) {
				throw new FieldValidationException("name",
						"Ein anderes Feld dieses Layers hat bereits den Namen '" + name + "'");
			}
		}

		field.rename(name);
		return toDto(field);
	}

	/**
	 * What deleting this field would touch, for the confirmation dialog (CONTRACT.md
	 * phase 12): how many objects would lose a value, and whether the layer's style
	 * classifies or labels by it. {@code count(column)} rather than {@code count(*)} --
	 * it counts exactly the non-null values, the objects a deletion actually affects.
	 */
	@Transactional(readOnly = true)
	public LayerDtos.FieldUsage usage(UUID layerId, UUID fieldId) {
		Layer layer = requireLayer(layerId);
		LayerField field = requireField(layerId, fieldId);

		long valueCount = jdbc.sql("SELECT count(%s) FROM %s".formatted(
						SqlIdentifier.quoteColumn(field.getColumnName()),
						SqlIdentifier.quoteLayerTable(layer.getTableName())))
				.query(Long.class)
				.single();

		LayerStyleService.FieldUsage styleUsage = styleService.fieldUsage(layer.getStyle(), field.getColumnName());
		return new LayerDtos.FieldUsage(valueCount, styleUsage.usedByRenderer(), styleUsage.usedByLabels());
	}

	/**
	 * Drops a field for good: the physical column, its {@code layer_field} row and, if
	 * the style depended on it, the reference in {@code layer.style} -- all in one
	 * transaction, so a crash midway never leaves a style pointing at a column that no
	 * longer exists (CONTRACT.md phase 12, "die Sackgasse").
	 *
	 * <p>No business rule stands in the way here, unlike {@link #addField} or
	 * {@link #renameField}: the contract is explicit that deleting the last field of a
	 * layer, or one currently classified on, is allowed outright. The confirmation is
	 * the frontend's job, informed by {@link #usage}, not a check this method repeats.
	 */
	@Transactional
	public void deleteField(UUID layerId, UUID fieldId, String clientName) {
		Layer layer = requireLayer(layerId);
		List<LayerField> fields = fieldRepository.findByLayerIdOrderByOrdinalAsc(layerId);
		LayerField field = fields.stream()
				.filter(candidate -> candidate.getId().equals(fieldId))
				.findFirst()
				.orElseThrow(() -> new NotFoundException("Feld " + fieldId + " existiert nicht"));

		// Captured against the full field list, exactly like applyStyle captures "before"
		// against the layer's current fields -- the point of comparison is what the
		// currently stored style resolves to right now, prior to anything this method does.
		Set<String> before = styleService.tileColumns(layer.getStyle(), fields);

		tableCreator.dropColumn(layer.getTableName(), field.getColumnName());
		fieldRepository.delete(field);

		List<LayerField> remaining = fields.stream()
				.filter(candidate -> !candidate.getId().equals(fieldId))
				.toList();
		GeometryType geometryType = GeometryType.valueOf(layer.getGeometryType());
		String cleanedStyle = styleService.cleanupAfterFieldRemoval(
				layer.getStyle(), field.getColumnName(), remaining, geometryType);

		Set<String> after = styleService.tileColumns(cleanedStyle, remaining);

		layer.setStyle(cleanedStyle);
		if (!before.equals(after)) {
			layer.bumpStyleVersion();
		}

		changeLog.record(layer.getProject().getId(), layer.getId(), layer.getName(),
				ChangeLogAction.FIELD_DELETE, clientName, 1, null);
	}

	// --- internals -----------------------------------------------------------------

	private LayerField requireField(UUID layerId, UUID fieldId) {
		return fieldRepository.findByLayerIdOrderByOrdinalAsc(layerId).stream()
				.filter(candidate -> candidate.getId().equals(fieldId))
				.findFirst()
				.orElseThrow(() -> new NotFoundException("Feld " + fieldId + " existiert nicht"));
	}

	/**
	 * Derives the column name for a brand-new field and rejects it outright if that
	 * normalises to a column this layer already has (CONTRACT.md phase 11, trap 1).
	 *
	 * <p>{@link SqlIdentifier#toColumnName} would happily resolve such a clash itself by
	 * appending a numbered suffix -- exactly right when several fields are proposed
	 * together at layer creation, since nothing there yet has an established identity to
	 * collide with. Here it would silently create "groesse_1" next to an existing
	 * "groesse" from a field spelled "Groesse", which reads as a bug, not a feature: a
	 * single field added on its own either is a genuinely new attribute or it is a typo
	 * for one that is already there, and only the person adding it can tell which.
	 */
	private String resolveNewColumnName(String name, List<LayerField> existing) {
		Set<String> existingColumns = existing.stream()
				.map(LayerField::getColumnName)
				.collect(Collectors.toCollection(LinkedHashSet::new));

		String candidate = SqlIdentifier.toColumnName(name, Set.of());
		if (existingColumns.contains(candidate)) {
			throw new FieldValidationException("name", "Der Feldname '" + name
					+ "' ergibt denselben Spaltennamen wie ein vorhandenes Feld. Wählen Sie einen anderen Namen.");
		}
		return candidate;
	}

	private FieldType parseFieldType(String raw) {
		try {
			return FieldType.valueOf(raw);
		}
		catch (IllegalArgumentException e) {
			throw new FieldValidationException("type", "Unbekannter Feldtyp: " + raw);
		}
	}

	/** Every field operation here touches the payload table, so a map image (kind WMS) is rejected up front. */
	private Layer requireLayer(UUID layerId) {
		Layer layer = layerRepository.findById(layerId)
				.orElseThrow(() -> new NotFoundException("Layer " + layerId + " existiert nicht"));
		layer.requireVector();
		return layer;
	}

	private static LayerDtos.Field toDto(LayerField field) {
		return new LayerDtos.Field(field.getId(), field.getSourceName(), field.getColumnName(),
				field.getDataType());
	}
}
