package de.kreuter.hgis.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;
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

	@Column(columnDefinition = "geometry(Polygon,4326)")
	private Polygon extent;

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

	public Polygon getExtent() {
		return extent;
	}

	public void setExtent(Polygon extent) {
		this.extent = extent;
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
