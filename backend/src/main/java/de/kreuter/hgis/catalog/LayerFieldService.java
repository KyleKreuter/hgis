package de.kreuter.hgis.catalog;

import de.kreuter.hgis.catalog.dto.LayerDtos;
import de.kreuter.hgis.common.FieldType;
import de.kreuter.hgis.common.FieldValidationException;
import de.kreuter.hgis.common.NotFoundException;
import de.kreuter.hgis.common.SqlIdentifier;
import de.kreuter.hgis.common.TableCreator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adds attribute fields to an existing layer and renames them -- the two schema changes
 * CONTRACT.md phase 11 allows once a layer already has a physical table. Deleting a
 * field, changing its type or reordering it are explicitly out of scope: nothing here
 * ever narrows a table or touches a column {@link TableCreator} already created, it only
 * ever widens one or relabels an entry in the catalog.
 *
 * <p>No {@code data_version} or {@code style_version} bump happens here. A new column
 * carries no data yet -- the frontend invalidates its own feature cache after adding one
 * -- and a rename only ever changes {@code source_name}, which neither tiles nor a
 * stored style (both keyed by {@code column_name}) ever see.
 */
@Service
public class LayerFieldService {

	/** Same ceiling as {@link LayerService#create}: a schema, not a dump, however it grew. */
	private static final int MAX_FIELDS = 50;

	private final LayerRepository layerRepository;
	private final LayerFieldRepository fieldRepository;
	private final TableCreator tableCreator;

	LayerFieldService(LayerRepository layerRepository, LayerFieldRepository fieldRepository,
			TableCreator tableCreator) {
		this.layerRepository = layerRepository;
		this.fieldRepository = fieldRepository;
		this.tableCreator = tableCreator;
	}

	/**
	 * Widens a layer's payload table by one column and records it in the catalog.
	 * Existing objects read back {@code NULL} for it -- not a migration concern, just
	 * what a freshly added column always contains.
	 */
	@Transactional
	public LayerDtos.Field addField(UUID layerId, LayerDtos.AddFieldRequest request) {
		Layer layer = requireLayer(layerId);
		List<LayerField> existing = fieldRepository.findByLayerIdOrderByOrdinalAsc(layerId);
		if (existing.size() >= MAX_FIELDS) {
			throw new FieldValidationException("name",
					"Der Layer hat bereits " + MAX_FIELDS + " Felder, mehr sind nicht erlaubt");
		}

		String name = request.name().trim();
		FieldType type = parseFieldType(request.type());
		String columnName = resolveNewColumnName(name, existing);

		tableCreator.addColumn(layer.getTableName(), columnName, type.pgType());

		int ordinal = fieldRepository.maxOrdinal(layerId) + 1;
		LayerField field = fieldRepository.save(
				new LayerField(layer, name, columnName, type.pgType(), ordinal));
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
						"Der Feldname '" + name + "' wird bereits von einem anderen Feld dieses Layers verwendet");
			}
		}

		field.rename(name);
		return toDto(field);
	}

	// --- internals -----------------------------------------------------------------

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
					+ "' ergibt denselben Spaltennamen wie ein bereits vorhandenes Feld dieses Layers");
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

	private Layer requireLayer(UUID layerId) {
		return layerRepository.findById(layerId)
				.orElseThrow(() -> new NotFoundException("Layer " + layerId + " existiert nicht"));
	}

	private static LayerDtos.Field toDto(LayerField field) {
		return new LayerDtos.Field(field.getId(), field.getSourceName(), field.getColumnName(),
				field.getDataType());
	}
}
