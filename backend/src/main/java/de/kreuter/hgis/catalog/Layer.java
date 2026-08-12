package de.kreuter.hgis.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
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

	/**
	 * Null, or this layer's role as one of the project's clip masks: {@code
	 * "insideWhole"}, {@code "insideClipped"}, {@code "outsideWhole"} or {@code
	 * "outsideClipped"} (CONTRACT.md phase 21). Any number of layers in a project may
	 * carry a non-null value at once; each acts on every layer above it (higher {@code
	 * zIndex}), and where several act on the same layer their effects combine -- see
	 * {@link #effectiveMasks}. Independent of {@code visible}: a hidden mask still clips
	 * everything above it, since the clip is not what draws it.
	 *
	 * <p>Not validated against an enum here -- the database CHECK constraint from
	 * {@code V6__clip_modes.sql} is the single source of truth for which tokens are legal,
	 * the same way {@link #geometryType} and {@link #basemap} are held as plain strings.
	 */
	@Column(name = "clip_mode")
	private String clipMode;

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

	/** Whether this layer currently is one of the project's clip masks, in any mode. */
	public boolean isMask() {
		return clipMode != null;
	}

	/**
	 * Null, or {@code "insideWhole"}, {@code "insideClipped"}, {@code "outsideWhole"} or
	 * {@code "outsideClipped"} -- see {@link #clipMode}.
	 */
	public String getClipMode() {
		return clipMode;
	}

	public void setClipMode(String clipMode) {
		this.clipMode = clipMode;
	}

	/**
	 * Whether {@code maskLayer} clips this layer's tiles (CONTRACT.md phase 21): it has
	 * to be marked, be a different layer than this one -- a mask never clips itself --
	 * and sit below this layer in the stack, since a mask only reaches upward. Its mode
	 * decides how the clip cuts, not whether it applies.
	 *
	 * @param maskLayer a candidate clip mask, or {@code null} if none is given
	 */
	public boolean isClippedBy(Layer maskLayer) {
		return maskLayer != null
				&& !maskLayer.id.equals(this.id)
				&& this.zIndex > maskLayer.zIndex;
	}

	/**
	 * The masks from {@code projectMasks} that act on this layer, unterste zuerst
	 * (CONTRACT.md phase 21): every one this layer sits above, in the same order
	 * {@code projectMasks} gave them in. Filters with {@link #isClippedBy}, so a mask
	 * never appears here for itself, and {@code projectMasks} may safely include this
	 * layer along with every other mask of the project.
	 *
	 * @param projectMasks every mask of the project, as {@link LayerRepository#findClipMasks}
	 *                     returns them -- unterste zuerst
	 */
	public List<Layer> effectiveMasks(List<Layer> projectMasks) {
		return projectMasks.stream()
				.filter(this::isClippedBy)
				.toList();
	}

	/**
	 * The version component the tile URL and its {@code ETag} carry for this layer's
	 * clip state (CONTRACT.md phase 21). Computed fresh from the live catalog on every
	 * call rather than stored, so marking, unmarking, editing or reordering any mask
	 * takes effect on the very next read -- nothing to invalidate on the way.
	 *
	 * <p>Zero exactly when {@link #effectiveMasks} is empty. That is the rest state, and
	 * it has to land on exactly 0: the client reads {@code clipVersion > 0} as "a clip
	 * applies". Otherwise a rolling hash over the effective masks, in their given order,
	 * folding in each mask's identity, its {@code dataVersion} and its {@code clipMode}:
	 * {@code dataVersion} alone would let two different masks that happen to share a data
	 * version number collide, and omitting {@code clipMode} would leave the tile address
	 * unchanged when a mask switches sides -- a client would then keep serving the old,
	 * wrongly clipped tile from cache. The hash multiplies and adds rather than XORs
	 * across masks, on purpose: XOR would let two equal contributions cancel each other
	 * out, so adding a second mask identical in effect to one already in the stack would
	 * leave the version -- and so the cached tile address -- unchanged.
	 *
	 * @param projectMasks every mask of the project, unterste zuerst -- see {@link
	 *                     #effectiveMasks}
	 */
	public long clipVersion(List<Layer> projectMasks) {
		List<Layer> masks = effectiveMasks(projectMasks);
		if (masks.isEmpty()) {
			return 0;
		}
		long hash = 1;
		for (Layer mask : masks) {
			UUID maskId = mask.id;
			long contribution = maskId.getMostSignificantBits() ^ maskId.getLeastSignificantBits()
					^ mask.dataVersion ^ mask.clipMode.hashCode();
			hash = hash * 31 + contribution;
		}
		// A non-empty mask stack must never land on the same value as the empty one --
		// astronomically unlikely on its own, but a single guard costs nothing and turns
		// "unlikely" into "impossible".
		return hash == 0 ? 1 : hash;
	}

	public Polygon getExtent() {
		return extent;
	}

	public void setExtent(Polygon extent) {
		this.extent = extent;
	}

	/**
	 * @param clipMode the source's clip mode -- null, or one of the four tokens
	 *                 {@link #clipMode} documents (CONTRACT.md phase 21). A duplicate is
	 *                 a project of its own, so copying this is safe: the source project
	 *                 keeps its own masks untouched, and every layer of the target
	 *                 -- mask or not -- is copied the same way, one call per layer, so a
	 *                 project with several masks keeps every one of them.
	 */
	public void setCopyMetadata(long featureCount, boolean visible, int zIndex, int minZoom,
			int maxZoom, String style, String basemap, Double basemapOpacity, String clipMode, Polygon extent) {
		this.featureCount = featureCount;
		this.visible = visible;
		this.zIndex = zIndex;
		this.minZoom = minZoom;
		this.maxZoom = maxZoom;
		this.style = style;
		this.basemap = basemap;
		this.basemapOpacity = basemapOpacity;
		this.clipMode = clipMode;
		this.extent = extent;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
