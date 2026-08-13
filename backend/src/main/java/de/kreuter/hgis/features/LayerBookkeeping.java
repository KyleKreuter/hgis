package de.kreuter.hgis.features;

import de.kreuter.hgis.catalog.Layer;
import de.kreuter.hgis.catalog.LayerRepository;
import de.kreuter.hgis.common.ExtentCalculator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Updates what a layer says about itself after a write to its payload table: the tile
 * cache buster, the feature count and the extent.
 *
 * <p>Without the version bump the map would keep serving the cached tiles that still show
 * the old geometry, which is why every write path owes this call -- the edit batch of
 * CONTRACT.md section 10 as much as the split and merge of section 12. One place for it,
 * so a new write path cannot forget half of it.
 *
 * <p>Runs inside the caller's transaction; it opens none of its own.
 */
@Component
class LayerBookkeeping {

	private final LayerRepository layerRepository;
	private final ExtentCalculator extentCalculator;
	private final JdbcClient jdbc;

	LayerBookkeeping(LayerRepository layerRepository, ExtentCalculator extentCalculator, JdbcClient jdbc) {
		this.layerRepository = layerRepository;
		this.extentCalculator = extentCalculator;
		this.jdbc = jdbc;
	}

	/**
	 * @param layer the managed catalog row; its {@code dataVersion} is readable straight
	 *              after this call
	 * @param quotedTable the payload table, already through {@code SqlIdentifier}
	 * @return the layer's recounted feature count
	 */
	long recount(Layer layer, String quotedTable) {
		long featureCount = jdbc.sql("SELECT COUNT(*) FROM " + quotedTable).query(Long.class).single();

		layer.setFeatureCount(featureCount);
		layer.bumpDataVersion();
		layer.setExtent(extentCalculator.forLayer(layer.getTableName(), layer.getSrid()));
		layerRepository.flush();

		return featureCount;
	}
}
