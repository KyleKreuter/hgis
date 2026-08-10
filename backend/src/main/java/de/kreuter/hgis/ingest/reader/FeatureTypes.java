package de.kreuter.hgis.ingest.reader;

import de.kreuter.hgis.ingest.spi.SourceField;
import java.util.ArrayList;
import java.util.List;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.api.feature.type.AttributeDescriptor;

/** Turns a GeoTools {@link SimpleFeatureType} into the SPI's attribute list, file order kept. */
final class FeatureTypes {

	private FeatureTypes() {
	}

	static List<SourceField> attributeFields(SimpleFeatureType featureType) {
		List<SourceField> result = new ArrayList<>();
		for (AttributeDescriptor descriptor : featureType.getAttributeDescriptors()) {
			if (descriptor.equals(featureType.getGeometryDescriptor())) {
				continue;
			}
			result.add(new SourceField(descriptor.getLocalName(), descriptor.getType().getBinding()));
		}
		return result;
	}
}
