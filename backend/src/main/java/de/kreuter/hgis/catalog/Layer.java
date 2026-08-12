package de.kreuter.hgis.catalog;

import de.kreuter.hgis.common.LayerProvenance;
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

	/**
	 * {@code 2^53 - 1}, the largest integer a JavaScript number holds exactly. Any version
	 * this class computes for the client is folded into it -- see {@link #clipVersion}.
	 */
	private static final long MAX_SAFE_INTEGER = 9007199254740991L;

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

	// --- Geoportal provenance (CONTRACT.md phase 23.7) -----------------------------------
	// All eight null together for a layer not imported from the Geoportal -- see
	// V7__layer_source.sql. Kept as flat columns like basemap and clipMode above rather
	// than a JSON blob: every one of them is either shown to the user as plain text or
	// linked as a URL, none is ever queried or filtered on, so there is nothing a nested
	// document would buy that a handful of columns does not already give for free.

	@Column(name = "source_attribution")
	private String sourceAttribution;

	@Column(name = "source_license_name")
	private String sourceLicenseName;

	@Column(name = "source_license_url")
	private String sourceLicenseUrl;

	@Column(name = "source_dataset_uri")
	private String sourceDatasetUri;

	@Column(name = "source_metadata_url")
	private String sourceMetadataUrl;

	/** The Geoportal catalog id this layer was imported from, e.g. {@code strassenbaumkataster/strassenbaumkataster_hh}. */
	@Column(name = "source_dataset_id")
	private String sourceDatasetId;

	/** Technical name of the field carrying the service's own stable feature id (decision E6), or null if it has none. */
	@Column(name = "source_feature_id_field")
	private String sourceFeatureIdField;

	@Column(name = "source_fetched_at")
	private Instant sourceFetchedAt;

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
	 * The masks from {@code projectMasks} that act on this layer, bottom-most first
	 * (CONTRACT.md phase 21): every one this layer sits above, in the same order
	 * {@code projectMasks} gave them in. Filters with {@link #isClippedBy}, so a mask
	 * never appears here for itself, and {@code projectMasks} may safely include this
	 * layer along with every other mask of the project.
	 *
	 * @param projectMasks every mask of the project, as {@link LayerRepository#findClipMasks}
	 *                     returns them -- bottom-most first
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
	 * <p>The result is folded into 53 bits, and that is not cosmetic. This value travels
	 * to the browser as a JSON number and ends up in the tile URL, where JavaScript holds
	 * it as a double: anything past 2^53 is rounded to the nearest representable value,
	 * in this range a multiple of 1024. Two mask stacks whose full 64-bit hashes differ by
	 * less than that would reach the client as the same number, produce the same tile URL,
	 * and -- since tiles are served {@code immutable} -- never be re-fetched at all. The
	 * ETag would still be right and would never be consulted, because a client with a
	 * matching immutable URL does not ask. 53 bits is still far more than cache-busting
	 * needs; exactness on the client side is worth more here than the extra 11 bits.
	 *
	 * @param projectMasks every mask of the project, bottom-most first -- see {@link
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
		// Fold into the range JavaScript can hold exactly (see above), then keep the
		// rest state to itself: a non-empty mask stack must never land on the same value
		// as the empty one. Astronomically unlikely on its own, but one guard costs
		// nothing and turns "unlikely" into "impossible".
		long safe = hash & MAX_SAFE_INTEGER;
		return safe == 0 ? 1 : safe;
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
	 * @param source   the source layer's Geoportal provenance (CONTRACT.md phase 23.7,
	 *                 {@link #getProvenance()}), or null for a layer that was drawn by
	 *                 hand or imported from a file. Without this a duplicated Geoportal
	 *                 layer would show no attribution at all, even though the data still
	 *                 carries the licence's clause 2 obligation -- copying loses nothing
	 *                 about where a layer's data came from, only its editing history.
	 */
	public void setCopyMetadata(long featureCount, boolean visible, int zIndex, int minZoom,
			int maxZoom, String style, String basemap, Double basemapOpacity, String clipMode, Polygon extent,
			LayerProvenance source) {
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
		if (source != null) {
			setSource(source.attribution(), source.licenseName(), source.licenseUrl(), source.datasetUri(),
					source.metadataUrl(), source.datasetId(), source.featureIdField(), source.fetchedAt());
		}
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	// --- Geoportal provenance (CONTRACT.md phase 23.7) -----------------------------------

	public String getSourceAttribution() {
		return sourceAttribution;
	}

	public String getSourceLicenseName() {
		return sourceLicenseName;
	}

	public String getSourceLicenseUrl() {
		return sourceLicenseUrl;
	}

	public String getSourceDatasetUri() {
		return sourceDatasetUri;
	}

	public String getSourceMetadataUrl() {
		return sourceMetadataUrl;
	}

	public String getSourceDatasetId() {
		return sourceDatasetId;
	}

	public String getSourceFeatureIdField() {
		return sourceFeatureIdField;
	}

	public Instant getSourceFetchedAt() {
		return sourceFetchedAt;
	}

	/**
	 * Written once, right after a Geoportal import creates this layer (CONTRACT.md phase
	 * 23.6); nothing updates it afterwards. {@code datasetId} and {@code featureIdField}
	 * exist for stage 5's future reconcile and are shown nowhere (CONTRACT.md 11.7); the
	 * other six are what clause 2 of the licence requires displayed, at the two places
	 * CONTRACT.md 11.7 names.
	 */
	public void setSource(String attribution, String licenseName, String licenseUrl, String datasetUri,
			String metadataUrl, String datasetId, String featureIdField, Instant fetchedAt) {
		this.sourceAttribution = attribution;
		this.sourceLicenseName = licenseName;
		this.sourceLicenseUrl = licenseUrl;
		this.sourceDatasetUri = datasetUri;
		this.sourceMetadataUrl = metadataUrl;
		this.sourceDatasetId = datasetId;
		this.sourceFeatureIdField = featureIdField;
		this.sourceFetchedAt = fetchedAt;
	}

	/**
	 * This layer's Geoportal provenance as one value, or null for a layer not imported from
	 * there. The marker is {@code sourceDatasetId}: the Geoportal catalog builds that id
	 * itself and always fills it, so it is set exactly when the layer came from there.
	 *
	 * <p>Not {@code sourceAttribution}, which the live service shows to be a different
	 * question: {@code grundwassermessstellen/grundwassermessstellen} (191,140 features,
	 * importable) carries a licence and a metadata record but no attribution at all, because
	 * the service directory leaves its agency blank. Keyed on attribution, duplicating such a
	 * project silently dropped the copy's entire provenance -- licence notice included, which
	 * is the one part CONTRACT.md 11.7 requires to be displayed. {@code LayerService#toSource}
	 * made the same wrong assumption and is keyed the same way now.
	 *
	 * <p>Used by {@link de.kreuter.hgis.catalog.ProjectDuplicateTransactions} to carry a
	 * layer's provenance into its copy via {@link #setCopyMetadata}.
	 */
	public LayerProvenance getProvenance() {
		if (sourceDatasetId == null) {
			return null;
		}
		return new LayerProvenance(sourceAttribution, sourceLicenseName, sourceLicenseUrl, sourceDatasetUri,
				sourceMetadataUrl, sourceDatasetId, sourceFeatureIdField, sourceFetchedAt);
	}
}
