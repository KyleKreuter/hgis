package de.kreuter.hgis.catalog;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LayerFieldRepository extends JpaRepository<LayerField, UUID> {

	List<LayerField> findByLayerIdOrderByOrdinalAsc(UUID layerId);

	/**
	 * Highest {@code ordinal} in use for a layer, or -1 for a layer with no fields yet --
	 * analogous to {@link LayerRepository#maxZIndex}. A field added later than the
	 * layer's creation goes after every existing one; nothing here ever renumbers the
	 * fields that came before it.
	 */
	@Query("SELECT COALESCE(MAX(f.ordinal), -1) FROM LayerField f WHERE f.layer.id = :layerId")
	int maxOrdinal(@Param("layerId") UUID layerId);
}
