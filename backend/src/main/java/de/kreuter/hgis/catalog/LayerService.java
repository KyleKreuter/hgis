package de.kreuter.hgis.catalog;

import de.kreuter.hgis.catalog.dto.LayerDtos;
import de.kreuter.hgis.common.BadRequestException;
import de.kreuter.hgis.common.NotFoundException;
import de.kreuter.hgis.common.SqlIdentifier;
import java.util.List;
import java.util.UUID;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Polygon;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LayerService {

	private final LayerRepository layerRepository;
	private final LayerFieldRepository fieldRepository;
	private final ProjectRepository projectRepository;
	private final JdbcClient jdbc;

	LayerService(LayerRepository layerRepository, LayerFieldRepository fieldRepository,
			ProjectRepository projectRepository, JdbcClient jdbc) {
		this.layerRepository = layerRepository;
		this.fieldRepository = fieldRepository;
		this.projectRepository = projectRepository;
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public List<LayerDtos.Summary> listByProject(UUID projectId) {
		if (!projectRepository.existsById(projectId)) {
			throw new NotFoundException("Projekt " + projectId + " existiert nicht");
		}
		return layersByProjectOrdered(projectId).stream()
				.map(LayerService::toSummary)
				.toList();
	}

	@Transactional(readOnly = true)
	public LayerDtos.Detail get(UUID layerId) {
		return toDetail(require(layerId));
	}

	@Transactional
	public LayerDtos.Detail update(UUID layerId, LayerDtos.UpdateRequest request) {
		Layer layer = require(layerId);

		if (request.name() != null) {
			String name = request.name().trim();
			if (name.isEmpty()) {
				throw new BadRequestException("Name darf nicht leer sein");
			}
			layer.setName(name);
		}
		if (request.visible() != null) {
			layer.setVisible(request.visible());
		}
		if (request.zIndex() != null) {
			layer.setZIndex(request.zIndex());
		}

		// Cross-field check ahead of time: mirrors the layer_zoom_range CHECK constraint,
		// so a bad combination fails with a proper 400 instead of a raw constraint
		// violation surfacing as a 500 through the generic exception handler.
		int minZoom = request.minZoom() != null ? request.minZoom() : layer.getMinZoom();
		int maxZoom = request.maxZoom() != null ? request.maxZoom() : layer.getMaxZoom();
		if (minZoom > maxZoom) {
			throw new BadRequestException("minZoom darf maxZoom nicht überschreiten");
		}
		if (request.minZoom() != null) {
			layer.setMinZoom(request.minZoom());
		}
		if (request.maxZoom() != null) {
			layer.setMaxZoom(request.maxZoom());
		}

		// Flush so updatedAt (set by the database trigger / @UpdateTimestamp on write)
		// is current in the response, not the value from before this update.
		layerRepository.flush();
		return toDetail(layer);
	}

	@Transactional
	public void delete(UUID layerId) {
		Layer layer = require(layerId);

		// The physical table has to go while its name is still known -- deleting the
		// catalog row first would leave an orphan behind that nothing can name any more.
		// Same reasoning as ProjectDeletionService, just for a single layer. Both
		// statements run in one transaction; DDL is transactional in PostgreSQL, so a
		// failure here rolls back cleanly.
		jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(layer.getTableName()))
				.update();
		layerRepository.delete(layer);
	}

	// --- helpers ---------------------------------------------------------------

	private Layer require(UUID layerId) {
		return layerRepository.findById(layerId)
				.orElseThrow(() -> new NotFoundException("Layer " + layerId + " existiert nicht"));
	}

	private List<Layer> layersByProjectOrdered(UUID projectId) {
		return layerRepository.findByProjectOrdered(projectId);
	}

	private static LayerDtos.Summary toSummary(Layer layer) {
		return new LayerDtos.Summary(
				layer.getId(), layer.getName(), layer.getGeometryType(), layer.getSrid(),
				layer.getFeatureCount(), layer.isVisible(), layer.getZIndex(),
				layer.getMinZoom(), layer.getMaxZoom(),
				layer.getDataVersion(), layer.getStyleVersion(),
				toBbox(layer.getExtent()));
	}

	private LayerDtos.Detail toDetail(Layer layer) {
		List<LayerDtos.Field> fields = fieldRepository.findByLayerIdOrderByOrdinalAsc(layer.getId()).stream()
				.map(f -> new LayerDtos.Field(f.getId(), f.getSourceName(), f.getColumnName(), f.getDataType()))
				.toList();

		return new LayerDtos.Detail(
				layer.getId(), layer.getName(), layer.getGeometryType(), layer.getSrid(),
				layer.getFeatureCount(), layer.isVisible(), layer.getZIndex(),
				layer.getMinZoom(), layer.getMaxZoom(),
				layer.getDataVersion(), layer.getStyleVersion(),
				toBbox(layer.getExtent()),
				fields, layer.getStyle(),
				layer.getCreatedAt(), layer.getUpdatedAt());
	}

	private static double[] toBbox(Polygon polygon) {
		if (polygon == null) {
			return null;
		}
		Envelope e = polygon.getEnvelopeInternal();
		return new double[] { e.getMinX(), e.getMinY(), e.getMaxX(), e.getMaxY() };
	}
}
