package de.kreuter.hgis.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * Maps a source attribute name to the sanitised SQL column name.
 *
 * This is the security hinge of the whole dynamic-DDL design: clients address
 * attributes by field id or source name, never by column name. Only the lookup here
 * produces a string that is allowed into SQL, and only after passing SqlIdentifier.
 */
@Entity
@Table(name = "layer_field")
public class LayerField {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "layer_id", nullable = false, updatable = false)
	private Layer layer;

	/** Original name from the file, shown in the UI. May contain umlauts and spaces. */
	@Column(name = "source_name", nullable = false, updatable = false)
	private String sourceName;

	/** Normalised, unique, safe to quote into SQL. */
	@Column(name = "column_name", nullable = false, updatable = false)
	private String columnName;

	/** PostgreSQL type as written in the DDL, e.g. text, bigint, double precision. */
	@Column(name = "data_type", nullable = false, updatable = false)
	private String dataType;

	@Column(nullable = false, updatable = false)
	private int ordinal;

	protected LayerField() {
		// for JPA
	}

	public LayerField(Layer layer, String sourceName, String columnName, String dataType,
			int ordinal) {
		this.layer = layer;
		this.sourceName = sourceName;
		this.columnName = columnName;
		this.dataType = dataType;
		this.ordinal = ordinal;
	}

	public UUID getId() {
		return id;
	}

	public Layer getLayer() {
		return layer;
	}

	public String getSourceName() {
		return sourceName;
	}

	public String getColumnName() {
		return columnName;
	}

	public String getDataType() {
		return dataType;
	}

	public int getOrdinal() {
		return ordinal;
	}
}
