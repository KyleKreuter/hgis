package de.kreuter.hgis.catalog;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LayerFieldRepository extends JpaRepository<LayerField, UUID> {

	List<LayerField> findByLayerIdOrderByOrdinalAsc(UUID layerId);
}
