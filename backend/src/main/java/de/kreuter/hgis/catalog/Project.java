package de.kreuter.hgis.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

/**
 * A project is the bracket around a set of layers -- the equivalent of a .qgz file
 * in QGIS. It owns the storage CRS, the last viewed map position and the layers
 * themselves.
 *
 * Geometries here are EPSG:4326 on purpose, unlike the payload tables in gis_data
 * which use the project's storage CRS. These two fields exist to drive the client:
 * MapLibre expects lng/lat, and pinning them to the storage CRS would break as soon
 * as a project uses a different one.
 */
@Entity
@Table(name = "project")
public class Project {

	@Id
	// UUIDv7: the timestamp sits in the high bits, so values sort chronologically and
	// inserts stay at the right edge of the B-tree. Style.TIME would produce v1, whose
	// low-order time field destroys exactly that locality.
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@Column(nullable = false)
	private String name;

	private String description;

	/**
	 * Storage CRS for every layer table of this project. Immutable: changing it would
	 * have to rewrite each payload table and rebuild every spatial index, so it is a
	 * deliberate migration rather than a field update.
	 */
	@Column(nullable = false, updatable = false)
	private int srid;

	@Column(columnDefinition = "geometry(Point,4326)")
	private Point center;

	private Double zoom;

	@Column(nullable = false)
	private String basemap = "osm";

	/** Opacity of the basemap itself, not of any layer's symbology. Every project has one. */
	@Column(name = "basemap_opacity", nullable = false)
	private double basemapOpacity = 1.0;

	@Column(columnDefinition = "geometry(Polygon,4326)")
	private Polygon extent;

	/**
	 * The client's view state -- active layer, and per layer what is sorted, searched or
	 * filtered, and selected. Opaque to this entity, like {@code Layer.style}: only
	 * {@link ProjectService} reads or writes what is actually inside it. Null means no
	 * state has ever been saved, not an error.
	 */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "view_state", columnDefinition = "jsonb")
	private String viewState;

	/**
	 * Rises with every write to {@link #viewState}, and with nothing else. The live
	 * channel ({@code de.kreuter.hgis.events}) reports this number instead of the state
	 * itself, the same way a layer's {@code dataVersion} stands in for its rows.
	 *
	 * <p>Read-only from here on purpose: {@link ProjectService#updateViewState} bumps it
	 * with a plain UPDATE so two writes at the same time cannot both read the same value
	 * and produce the same next one. {@code updatable = false} is what keeps Hibernate
	 * from writing this -- by then stale -- copy back over that.
	 */
	@Column(name = "view_state_version", nullable = false, updatable = false)
	private long viewStateVersion = 1;

	/**
	 * Rises with every write to any layer or field of this project -- everything about it
	 * that is not {@link #viewState}. Unlike {@link #viewStateVersion}, bumped by a
	 * database trigger on {@code layer} and {@code layer_field}
	 * ({@code V14__catalog_version.sql}), not by a plain UPDATE from Java -- see that
	 * migration for why. {@code updatable = false} for the same reason it holds for {@link
	 * #viewStateVersion}: Hibernate must never write a value back over one the trigger has
	 * since moved on.
	 */
	@Column(name = "catalog_version", nullable = false, updatable = false)
	private long catalogVersion = 1;

	@Column(name = "last_opened_at")
	private Instant lastOpenedAt;

	// A database trigger also maintains updated_at, so rows written through plain SQL
	// (data_version bumps) stay honest. Both paths set the same value.
	@CreationTimestamp
	@Column(name = "created_at", updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at")
	private Instant updatedAt;

	protected Project() {
		// for JPA
	}

	public Project(String name, String description, int srid, String basemap) {
		this.name = name;
		this.description = description;
		this.srid = srid;
		this.basemap = basemap == null ? "osm" : basemap;
	}

	public UUID getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public int getSrid() {
		return srid;
	}

	public Point getCenter() {
		return center;
	}

	public void setCenter(Point center) {
		this.center = center;
	}

	public Double getZoom() {
		return zoom;
	}

	public void setZoom(Double zoom) {
		this.zoom = zoom;
	}

	public String getBasemap() {
		return basemap;
	}

	public void setBasemap(String basemap) {
		this.basemap = basemap;
	}

	public double getBasemapOpacity() {
		return basemapOpacity;
	}

	public void setBasemapOpacity(double basemapOpacity) {
		this.basemapOpacity = basemapOpacity;
	}

	public Polygon getExtent() {
		return extent;
	}

	public void setExtent(Polygon extent) {
		this.extent = extent;
	}

	public String getViewState() {
		return viewState;
	}

	public void setViewState(String viewState) {
		this.viewState = viewState;
	}

	/**
	 * @return the version as it was when this entity was loaded. A bump that happened
	 *     since is not visible here -- {@link ProjectService#updateViewState} takes the
	 *     new value straight from the UPDATE that produced it.
	 */
	public long getViewStateVersion() {
		return viewStateVersion;
	}

	/**
	 * @return the version as it was when this entity was loaded. A trigger-driven bump
	 *     that happened since is not visible here -- {@code CatalogEventBridge} reads the
	 *     current value fresh with a plain query instead of through this entity.
	 */
	public long getCatalogVersion() {
		return catalogVersion;
	}

	public Instant getLastOpenedAt() {
		return lastOpenedAt;
	}

	public void setLastOpenedAt(Instant lastOpenedAt) {
		this.lastOpenedAt = lastOpenedAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
