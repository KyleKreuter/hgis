package de.kreuter.hgis.wms;

import de.kreuter.hgis.catalog.Layer;
import de.kreuter.hgis.catalog.LayerRepository;
import de.kreuter.hgis.catalog.LayerService;
import de.kreuter.hgis.catalog.Project;
import de.kreuter.hgis.catalog.ProjectRepository;
import de.kreuter.hgis.catalog.dto.LayerDtos;
import de.kreuter.hgis.changelog.ChangeLogAction;
import de.kreuter.hgis.changelog.ChangeLogService;
import de.kreuter.hgis.common.BadRequestException;
import de.kreuter.hgis.common.LayerProvenance;
import de.kreuter.hgis.common.NotFoundException;
import de.kreuter.hgis.common.Uuid7;
import de.kreuter.hgis.geoportal.GeoportalDatasetService;
import de.kreuter.hgis.wms.dto.MapLayerDtos;
import de.kreuter.hgis.wms.dto.WmsDtos;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CONTRACT.md 3: creates a map image layer. Unlike a vector import, nothing is
 * downloaded -- the service's capabilities are re-read and re-checked (the chosen
 * layers still exist, EPSG:3857 is still offered) and the layer is stored immediately,
 * so this method answers synchronously with {@code 201} rather than dispatching a job.
 */
@Service
public class MapLayerService {

	/**
	 * The scale denominator at Web Mercator zoom 0, by the OGC's own standardised
	 * rendering pixel size of 0.28 mm (OGC 06-042, table 7): the ground distance one
	 * pixel of the world-in-256px zoom-0 tile covers (156543.033928 m at the equator)
	 * divided by that pixel size. WMS {@code MinScaleDenominator}/{@code
	 * MaxScaleDenominator} are expressed in exactly this unit, which is what makes the
	 * conversion in {@link #zoomRangeOf} a straight {@code log2} rather than a service
	 * specific guess.
	 */
	private static final double SCALE_DENOMINATOR_AT_ZOOM_0 = 559_082_264.028;

	private static final int MIN_ZOOM = 0;
	private static final int MAX_ZOOM = 22;

	private final ProjectRepository projectRepository;
	private final LayerRepository layerRepository;
	private final LayerService layerService;
	private final WmsCapabilitiesService capabilitiesService;
	private final GeoportalDatasetService geoportalDatasetService;
	private final GeometryFactory wgs84GeometryFactory;
	private final ChangeLogService changeLog;

	MapLayerService(ProjectRepository projectRepository, LayerRepository layerRepository, LayerService layerService,
			WmsCapabilitiesService capabilitiesService, GeoportalDatasetService geoportalDatasetService,
			GeometryFactory wgs84GeometryFactory, ChangeLogService changeLog) {
		this.projectRepository = projectRepository;
		this.layerRepository = layerRepository;
		this.layerService = layerService;
		this.capabilitiesService = capabilitiesService;
		this.geoportalDatasetService = geoportalDatasetService;
		this.wgs84GeometryFactory = wgs84GeometryFactory;
		this.changeLog = changeLog;
	}

	@Transactional
	public LayerDtos.Summary create(UUID projectId, MapLayerDtos.CreateRequest request, String clientName) {
		Project project = projectRepository.findById(projectId)
				.orElseThrow(() -> new NotFoundException("Projekt " + projectId + " existiert nicht"));

		// An unnamed grouping layer (WmsDtos.Layer#name() null, orchestrator amendment)
		// is a heading, not a choice -- @NotEmpty on the request only rejects an empty
		// list, never a null element inside a non-empty one, so a client echoing one back
		// from /api/wms/capabilities has to be caught here explicitly.
		if (request.layers().contains(null)) {
			throw new BadRequestException(
					"Die Layerliste darf keinen leeren Eintrag enthalten -- eine Gruppe ohne Namen hat nichts zu zeichnen.");
		}

		// Re-read, not trusted from an earlier /api/wms/capabilities call: the service
		// may have changed its layers or dropped EPSG:3857 in the meantime, and the
		// same guards (SSRF, version, EPSG:3857) have to run again either way.
		WmsDtos.CapabilitiesResponse capabilities = capabilitiesService.capabilities(request.serviceUrl());
		List<WmsDtos.Layer> chosen = resolveChosenLayers(request.layers(), capabilities);
		requireKnownFormat(request.imageFormat(), capabilities.imageFormats());

		String name = (request.name() == null || request.name().isBlank())
				? chosen.get(0).title()
				: request.name().trim();
		if (name == null || name.isBlank()) {
			throw new BadRequestException("name fehlt und der Dienst nennt keinen Titel für den ersten Layer");
		}

		String legendUrl = chosen.stream().map(WmsDtos.Layer::legendUrl).filter(Objects::nonNull).findFirst()
				.orElse(null);
		boolean queryable = chosen.stream().anyMatch(WmsDtos.Layer::queryable);

		UUID layerId = Uuid7.generate();
		Layer layer = new Layer(layerId, project, name, capabilities.serviceUrl(), request.layers(),
				request.imageFormat(), legendUrl, queryable);
		layer.setZIndex(layerRepository.maxZIndex(projectId) + 1);
		layer.setExtent(combinedExtent(chosen));

		int[] zoomRange = combinedZoomRange(chosen);
		if (zoomRange != null) {
			layer.setMinZoom(zoomRange[0]);
			layer.setMaxZoom(zoomRange[1]);
		}

		if (request.datasetId() != null && !request.datasetId().isBlank()) {
			LayerProvenance provenance = geoportalDatasetService.provenanceFor(request.datasetId());
			layer.setSource(provenance.attribution(), provenance.licenseName(), provenance.licenseUrl(),
					provenance.datasetUri(), provenance.metadataUrl(), provenance.datasetId(),
					provenance.featureIdField(), provenance.fetchedAt());
		}

		layer = layerRepository.save(layer);

		// No feature.insert: a map image has no payload table of its own to hold rows.
		changeLog.record(projectId, layer.getId(), layer.getName(), ChangeLogAction.LAYER_CREATE,
				clientName, 1, null);

		return layerService.getSummary(layer.getId());
	}

	/**
	 * Every requested name resolved against the service's own layer list, in the
	 * requested order.
	 *
	 * <p>{@code requestedName.equals(layer.name())}, not the other way round: the
	 * capabilities answer can now hold entries with a null {@code name()} of their own --
	 * unnamed grouping layers, listed as headings (orchestrator amendment) -- and
	 * {@code null.equals(requestedName)} would throw before the real, named entries are
	 * ever reached. The caller has already rejected a null {@code requestedName} outright.
	 */
	private static List<WmsDtos.Layer> resolveChosenLayers(List<String> requestedNames,
			WmsDtos.CapabilitiesResponse capabilities) {
		List<WmsDtos.Layer> chosen = new ArrayList<>(requestedNames.size());
		for (String requestedName : requestedNames) {
			WmsDtos.Layer match = capabilities.layers().stream()
					.filter(layer -> requestedName.equals(layer.name()))
					.findFirst()
					.orElseThrow(() -> new BadRequestException(
							"Der Dienst bietet keinen Layer namens '" + requestedName + "' an."));
			chosen.add(match);
		}
		return chosen;
	}

	private static void requireKnownFormat(String imageFormat, List<String> offered) {
		if (!offered.contains(imageFormat)) {
			throw new BadRequestException("Der Dienst bietet das Bildformat '" + imageFormat
					+ "' nicht an. Verfügbar: " + String.join(", ", offered) + ".");
		}
	}

	/** The union of the chosen layers' geographic bounding boxes, or null when none names one. */
	private Polygon combinedExtent(List<WmsDtos.Layer> chosen) {
		Envelope envelope = new Envelope();
		for (WmsDtos.Layer layer : chosen) {
			double[] bbox = layer.bbox();
			if (bbox != null) {
				envelope.expandToInclude(bbox[0], bbox[1]);
				envelope.expandToInclude(bbox[2], bbox[3]);
			}
		}
		if (envelope.isNull()) {
			return null;
		}
		return wgs84GeometryFactory.createPolygon(new Coordinate[] {
				new Coordinate(envelope.getMinX(), envelope.getMinY()),
				new Coordinate(envelope.getMaxX(), envelope.getMinY()),
				new Coordinate(envelope.getMaxX(), envelope.getMaxY()),
				new Coordinate(envelope.getMinX(), envelope.getMaxY()),
				new Coordinate(envelope.getMinX(), envelope.getMinY()),
		});
	}

	/**
	 * The union of the chosen layers' zoom ranges -- the composite image is drawn
	 * whenever any one of them would be -- or null when none of them names a scale
	 * limit at all, leaving the layer at its column defaults (0 to 22).
	 */
	private static int[] combinedZoomRange(List<WmsDtos.Layer> chosen) {
		Integer minZoom = null;
		Integer maxZoom = null;
		for (WmsDtos.Layer layer : chosen) {
			int[] range = zoomRangeOf(layer);
			if (range == null) {
				continue;
			}
			minZoom = minZoom == null ? range[0] : Math.min(minZoom, range[0]);
			maxZoom = maxZoom == null ? range[1] : Math.max(maxZoom, range[1]);
		}
		return minZoom == null ? null : new int[] { minZoom, maxZoom };
	}

	/**
	 * One layer's own zoom range from its scale denominators, or null when it names
	 * neither. A smaller scale denominator means more zoomed in (higher {@code z}), so
	 * {@code maxScale} -- the more zoomed-out edge of the layer's visible range --
	 * becomes {@code minZoom}, and {@code minScale} becomes {@code maxZoom}.
	 *
	 * <p>Two adjacent Web Mercator zoom levels differ by a factor of exactly two in
	 * scale denominator; a service can legitimately declare a narrower band than that
	 * (the plan's own measurement: Hamburg's {@code m100000_farbig} tier spans a factor
	 * of 1.67), which rounds {@code minZoom} above {@code maxZoom}. Collapsed to the one
	 * zoom level in between rather than left inverted -- an inverted range would fail
	 * the {@code layer_zoom_range} CHECK constraint outright, and a real, if narrow,
	 * layer is a better answer than none.
	 */
	// Package-private, not private: this is the arithmetic that decides whether a layer is
	// ever seen at all, and it needs a test of its own that does not stand up a service --
	// the same reasoning `resolveSelectedFeature` follows on the frontend side.
	static int[] zoomRangeOf(WmsDtos.Layer layer) {
		if (layer.minScale() == null && layer.maxScale() == null) {
			return null;
		}
		double latitude = centreLatitudeOf(layer.bbox());
		int minZoom = layer.maxScale() == null ? MIN_ZOOM : zoomAtOrBelow(layer.maxScale(), latitude);
		int maxZoom = layer.minScale() == null ? MAX_ZOOM : zoomAtOrAbove(layer.minScale(), latitude);
		if (minZoom > maxZoom) {
			int collapsed = (minZoom + maxZoom) / 2;
			return new int[] { collapsed, collapsed };
		}
		return new int[] { minZoom, maxZoom };
	}

	/**
	 * The middle latitude of a layer's own bounding box, or 0 when it declares none.
	 *
	 * <p>Needed because {@link #SCALE_DENOMINATOR_AT_ZOOM_0} holds at the equator only. A
	 * Web Mercator pixel covers {@code cos(latitude)} times less ground the further from
	 * it one goes, so the same zoom level is a finer scale in Hamburg than in Nairobi.
	 * Ignoring that cost roughly one zoom level at Hamburg's 53.5° -- measured on
	 * {@code m2500_farbig}, whose {@code MaxScaleDenominator} of 3000 came out as zoom 18
	 * instead of 16, hiding the layer across two whole levels where the service does draw.
	 */
	private static double centreLatitudeOf(double[] bbox) {
		if (bbox == null || bbox.length < 4) {
			return 0;
		}
		return (bbox[1] + bbox[3]) / 2;
	}

	/** The scale denominator one Web Mercator pixel spans at this zoom and latitude. */
	private static double scaleDenominatorAt(double zoom, double latitude) {
		return SCALE_DENOMINATOR_AT_ZOOM_0 * Math.cos(Math.toRadians(latitude)) / Math.pow(2, zoom);
	}

	/**
	 * The lowest zoom level whose scale is still within {@code maxScale} -- rounded
	 * <em>down</em>, deliberately.
	 *
	 * <p>Rounding to nearest, as the first cut did, hides the layer on the level where the
	 * service has already started drawing: at Hamburg's latitude a
	 * {@code MaxScaleDenominator} of 3000 lands on zoom 16.76, and rounding that to 17
	 * leaves the whole band from 16.0 to 17.0 blank even though the service answers there.
	 * A tile fetched a level too early costs one transparent image; a hidden layer looks
	 * like a broken import, which is exactly how this surfaced.
	 */
	private static int zoomAtOrBelow(double maxScale, double latitude) {
		double zoom = Math.log(scaleDenominatorAt(0, latitude) / maxScale) / Math.log(2);
		return Math.clamp((int) Math.floor(zoom), MIN_ZOOM, MAX_ZOOM);
	}

	/** The highest zoom level still within {@code minScale} -- rounded up, mirroring {@link #zoomAtOrBelow}. */
	private static int zoomAtOrAbove(double minScale, double latitude) {
		double zoom = Math.log(scaleDenominatorAt(0, latitude) / minScale) / Math.log(2);
		return Math.clamp((int) Math.ceil(zoom), MIN_ZOOM, MAX_ZOOM);
	}
}
