package de.kreuter.hgis.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Polygon;

/**
 * Catalog entry for one layer. The actual features live in a table of their own in
 * gis_data, named {@code layer_<hex of id>} and created at runtime -- this entity only
 * describes it.
 *
 * The extent is EPSG:4326 like all metadata geometry, so the client can zoom to a layer
 * without knowing the project's storage CRS.
 */
@Entity
@Table(name = "layer")
public class Layer {

	@Id
	@Column(nullable = false)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "project_id", nullable = false, updatable = false)
	private Project project;

	@Column(nullable = false)
	private String name;

	/** Always 'layer_' + hex(id). Never derived from user input. */
	@Column(name = "table_name", nullable = false, updatable = false)
	private String tableName;

	/** MULTIPOINT, MULTILINESTRING, MULTIPOLYGON or GEOMETRY for genuinely mixed sources. */
	@Column(name = "geometry_type", nullable = false, updatable = false)
	private String geometryType;

	@Column(nullable = false, updatable = false)
	private int srid;

	@Column(name = "feature_count", nullable = false)
	private long featureCount;

	/**
	 * Bumped by every write to the payload table. Part of the tile URL, so changed data
	 * produces a new URL and MapLibre reloads instead of serving stale cached tiles.
	 */
	@Column(name = "data_version", nullable = false)
	private long dataVersion = 1;

	/**
	 * Bumped only when a style change alters which attributes the tiles must carry.
	 * A pure colour change must not invalidate the cache -- it is applied client side.
	 */
	@Column(name = "style_version", nullable = false)
	private long styleVersion = 1;

	@Column(nullable = false)
	private boolean visible = true;

	@Column(name = "z_index", nullable = false)
	private int zIndex;

	@Column(name = "min_zoom", nullable = false)
	private int minZoom;

	@Column(name = "max_zoom", nullable = false)
	private int maxZoom = 22;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private String style;

	/**
	 * This layer's own basemap, or null to follow the project's. Null is not a default
	 * here but a distinct state -- see the layer_basemap_length check and CONTRACT.md
	 * phase 18. Not validated against a catalogue; the server does not know the token
	 * values, only their length.
	 */
	@Column(name = "basemap")
	private String basemap;

	/** This layer's own opacity for the basemap, or null to follow the project's. */
	@Column(name = "basemap_opacity")
	private Double basemapOpacity;

	@Column(columnDefinition = "geometry(Polygon,4326)")
	private Polygon extent;

	@CreationTimestamp
	@Column(name = "created_at", updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at")
	private Instant updatedAt;

	protected Layer() {
		// for JPA
	}

	public Layer(UUID id, Project project, String name, String tableName,
			String geometryType, int srid) {
		this.id = id;
		this.project = project;
		this.name = name;
		this.tableName = tableName;
		this.geometryType = geometryType;
		this.srid = srid;
	}

	public UUID getId() {
		return id;
	}

	public Project getProject() {
		return project;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getTableName() {
		return tableName;
	}

	public String getGeometryType() {
		return geometryType;
	}

	public int getSrid() {
		return srid;
	}

	public long getFeatureCount() {
		return featureCount;
	}

	public void setFeatureCount(long featureCount) {
		this.featureCount = featureCount;
	}

	public long getDataVersion() {
		return dataVersion;
	}

	public void bumpDataVersion() {
		this.dataVersion++;
	}

	public long getStyleVersion() {
		return styleVersion;
	}

	public void bumpStyleVersion() {
		this.styleVersion++;
	}

	public boolean isVisible() {
		return visible;
	}

	public void setVisible(boolean visible) {
		this.visible = visible;
	}

	public int getZIndex() {
		return zIndex;
	}

	public void setZIndex(int zIndex) {
		this.zIndex = zIndex;
	}

	public int getMinZoom() {
		return minZoom;
	}

	public void setMinZoom(int minZoom) {
		this.minZoom = minZoom;
	}

	public int getMaxZoom() {
		return maxZoom;
	}

	public void setMaxZoom(int maxZoom) {
		this.maxZoom = maxZoom;
	}

	public String getStyle() {
		return style;
	}

	public void setStyle(String style) {
		this.style = style;
	}

	public String getBasemap() {
		return basemap;
	}

	public void setBasemap(String basemap) {
		this.basemap = basemap;
	}

	public Double getBasemapOpacity() {
		return basemapOpacity;
	}

	public void setBasemapOpacity(Double basemapOpacity) {
		this.basemapOpacity = basemapOpacity;
	}

	public Polygon getExtent() {
		return extent;
	}

	public void setExtent(Polygon extent) {
		this.extent = extent;
	}

	public void setCopyMetadata(long featureCount, boolean visible, int zIndex, int minZoom,
			int maxZoom, String style, String basemap, Double basemapOpacity, Polygon extent) {
		this.featureCount = featureCount;
		this.visible = visible;
		this.zIndex = zIndex;
		this.minZoom = minZoom;
		this.maxZoom = maxZoom;
		this.style = style;
		this.basemap = basemap;
		this.basemapOpacity = basemapOpacity;
		this.extent = extent;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
